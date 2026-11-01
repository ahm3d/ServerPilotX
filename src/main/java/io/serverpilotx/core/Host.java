package io.serverpilotx.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A managed server. The local machine runs commands directly; remote servers run them over
 * SSH (key auth, multiplexed connection). Every module talks to the server only through this class,
 * so the whole app works the same on local and remote hosts.
 */
public final class Host {
    private static final String EXTRA_PATH = "/usr/local/sbin:/usr/sbin:/sbin";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(90);

    private final String id;
    private final String name;
    private final String address;   // empty for local
    private final String user;
    private final int port;
    private final String identityFile;

    private final Privilege privilege = new Privilege(this);
    private volatile Set<String> tools = Set.of();
    private volatile Map<String, String> osRelease = Map.of();
    private volatile String pkgManager = "";

    public Host(String id, String name, String address, String user, int port, String identityFile) {
        this.id = id;
        this.name = name;
        this.address = address == null ? "" : address.strip();
        this.user = (user == null || user.isBlank()) ? System.getProperty("user.name") : user.strip();
        this.port = port <= 0 ? 22 : port;
        this.identityFile = identityFile == null ? "" : identityFile.strip();
    }

    public static Host local() {
        return new Host("local", "This computer", "", System.getProperty("user.name"), 22, "");
    }

    public String id() { return id; }
    public String name() { return name; }
    public String address() { return address; }
    public String user() { return user; }
    public int port() { return port; }
    public String identityFile() { return identityFile; }
    public boolean isLocal() { return address.isEmpty(); }
    public boolean isRoot() { return "root".equals(user); }
    public Privilege privilege() { return privilege; }

    public String label() {
        return isLocal() ? name + " (local)" : name + "  ·  " + user + "@" + address;
    }

    // ------------------------------------------------------------------ exec

    /** argv for running a shell command string on this host via ssh. */
    public List<String> sshArgv(boolean tty, String remoteCommand) {
        List<String> a = new ArrayList<>(List.of("ssh",
                "-o", "ConnectTimeout=8",
                "-o", "ControlMaster=auto",
                "-o", "ControlPath=" + Settings.cacheDir().resolve("ssh-%C"),
                "-o", "ControlPersist=600",
                "-o", "StrictHostKeyChecking=accept-new",
                "-o", "ServerAliveInterval=20",
                "-p", String.valueOf(port)));
        a.add(tty ? "-t" : "-T");
        if (!tty) a.addAll(List.of("-o", "BatchMode=yes"));
        if (!identityFile.isEmpty()) a.addAll(List.of("-i", identityFile));
        a.add(user + "@" + address);
        if (remoteCommand != null) {
            a.add("--");
            a.add(remoteCommand);
        }
        return a;
    }

    private ProcessBuilder builder(List<String> argv) {
        List<String> full;
        if (isLocal()) {
            full = argv;
        } else {
            full = sshArgv(false, "LC_ALL=C PATH=\"$PATH:" + EXTRA_PATH + "\" " + Quote.join(argv));
        }
        ProcessBuilder pb = new ProcessBuilder(full);
        Map<String, String> env = pb.environment();
        env.put("LC_ALL", "C");
        env.put("PATH", env.getOrDefault("PATH", "/usr/bin:/bin") + ":" + EXTRA_PATH);
        return pb;
    }

    public CmdResult exec(List<String> argv, String stdin, Duration timeout) {
        Process p;
        try {
            p = builder(argv).start();
        } catch (IOException e) {
            return new CmdResult(127, "", "Cannot run " + argv.get(0) + ": " + e.getMessage());
        }
        CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> readAll(p.getInputStream()));
        CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> readAll(p.getErrorStream()));
        try (OutputStream os = p.getOutputStream()) {
            if (stdin != null) os.write(stdin.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // process may not read stdin
        }
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                return new CmdResult(124, out.getNow(""), "Timed out after " + timeout.toSeconds() + "s");
            }
            return new CmdResult(p.exitValue(), out.get(), err.get());
        } catch (Exception e) {
            p.destroyForcibly();
            return new CmdResult(130, "", "Interrupted");
        }
    }

    public CmdResult run(String... argv) {
        return exec(Arrays.asList(argv), null, DEFAULT_TIMEOUT);
    }

    public CmdResult runIn(String stdin, String... argv) {
        return exec(Arrays.asList(argv), stdin, DEFAULT_TIMEOUT);
    }

    /** Runs a shell script with sh -c. */
    public CmdResult sh(String script) {
        return exec(List.of("sh", "-c", script), null, DEFAULT_TIMEOUT);
    }

    /** Opens a brand-new SSH connection (bypassing the multiplexed one) to verify the server is still reachable. */
    public boolean probeFreshConnection() {
        if (isLocal()) return true;
        List<String> a = new ArrayList<>(List.of("ssh", "-o", "ControlMaster=no", "-o", "ControlPath=none", "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=10", "-o", "StrictHostKeyChecking=accept-new", "-p", String.valueOf(port)));
        if (!identityFile.isEmpty()) a.addAll(List.of("-i", identityFile));
        a.add(user + "@" + address);
        a.add("true");
        try {
            Process p = new ProcessBuilder(a).redirectErrorStream(true).start();
            p.getOutputStream().close();
            if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------- privileged

    public CmdResult sudo(String... argv) {
        return sudo(Arrays.asList(argv), DEFAULT_TIMEOUT);
    }

    public CmdResult sudoSh(String script) {
        return sudo(List.of("sh", "-c", script), DEFAULT_TIMEOUT);
    }

    public CmdResult sudo(List<String> argv, Duration timeout) {
        if (isRoot()) return exec(argv, null, timeout);
        if (isLocal() && Settings.privMethod() == Settings.PrivMethod.PKEXEC) {
            List<String> a = new ArrayList<>();
            a.add("pkexec");
            a.addAll(argv);
            return exec(a, null, timeout);
        }
        if (privilege.passwordless()) {
            List<String> a = new ArrayList<>(List.of("sudo", "-n", "--"));
            a.addAll(argv);
            return exec(a, null, timeout);
        }
        String pw = privilege.password();
        List<String> a = new ArrayList<>(List.of("sudo", "-S", "-p", "", "--"));
        a.addAll(argv);
        CmdResult r = exec(a, pw + "\n", timeout);
        if (!r.ok() && Privilege.isAuthFailure(r.err())) {
            privilege.clear();
            throw new CmdException("Administrator password was rejected.");
        }
        return r;
    }

    /** Starts a long-running command with merged stdout/stderr for streaming output. */
    public Process stream(List<String> argv, boolean asRoot) throws IOException {
        List<String> a = new ArrayList<>();
        String pw = null;
        if (asRoot && !isRoot()) {
            if (isLocal() && Settings.privMethod() == Settings.PrivMethod.PKEXEC) {
                a.add("pkexec");
            } else if (privilege.passwordless()) {
                a.addAll(List.of("sudo", "-n", "--"));
            } else {
                pw = privilege.password();
                a.addAll(List.of("sudo", "-S", "-p", "", "--"));
            }
        }
        a.addAll(argv);
        ProcessBuilder pb = builder(a).redirectErrorStream(true);
        Process p = pb.start();
        OutputStream os = p.getOutputStream();
        if (pw != null) {
            os.write((pw + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
        os.close();
        return p;
    }

    // ------------------------------------------------------------------ files

    private List<String> rootPrefix(boolean asRoot, String[] pwOut) {
        List<String> a = new ArrayList<>();
        if (!asRoot || isRoot()) return a;
        if (isLocal() && Settings.privMethod() == Settings.PrivMethod.PKEXEC) a.add("pkexec");
        else if (privilege.passwordless()) a.addAll(List.of("sudo", "-n", "--"));
        else {
            pwOut[0] = privilege.password();
            a.addAll(List.of("sudo", "-S", "-p", "", "--"));
        }
        return a;
    }

    /** Copies a local file to the host (binary safe). */
    public void upload(java.nio.file.Path local, String remotePath, boolean asRoot) {
        String tmp = run("mktemp", "/tmp/spx-up.XXXXXXXX").orThrow().strip();
        try {
            Process p = builder(List.of("sh", "-c", "cat > \"$1\"", "sh", tmp)).redirectErrorStream(true).start();
            try (OutputStream os = p.getOutputStream()) {
                java.nio.file.Files.copy(local, os);
            }
            String out = readAll(p.getInputStream());
            if (p.waitFor() != 0) throw new CmdException("Upload failed: " + out);
            if (asRoot) sudo("cp", "--", tmp, remotePath).orThrow();
            else run("cp", "--", tmp, remotePath).orThrow();
        } catch (IOException | InterruptedException e) {
            throw new CmdException("Upload failed: " + e.getMessage(), e);
        } finally {
            run("rm", "-f", tmp);
        }
    }

    /** Copies a file from the host to a local path (binary safe). */
    public void download(String remotePath, java.nio.file.Path local, boolean asRoot) {
        String[] pw = new String[1];
        List<String> a = rootPrefix(asRoot, pw);
        a.addAll(List.of("cat", "--", remotePath));
        try {
            java.io.File err = java.io.File.createTempFile("spx-dl", ".err");
            Process p = builder(a).redirectOutput(local.toFile()).redirectError(err).start();
            try (OutputStream os = p.getOutputStream()) {
                if (pw[0] != null) os.write((pw[0] + "\n").getBytes(StandardCharsets.UTF_8));
            }
            int code = p.waitFor();
            String e = java.nio.file.Files.readString(err.toPath());
            err.delete();
            if (code != 0) throw new CmdException("Download failed: " + e.strip());
        } catch (IOException | InterruptedException e) {
            throw new CmdException("Download failed: " + e.getMessage(), e);
        }
    }

    /** A persistent shell (bash -s) with open stdin, for the built-in terminal. */
    public Process shell() throws IOException {
        List<String> argv = isLocal()
                ? List.of("bash", "--noprofile", "--norc", "-s")
                : sshArgv(false, "exec bash --noprofile --norc -s 2>&1 || exec sh -s");
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        pb.environment().put("TERM", "dumb");
        pb.environment().put("PATH", pb.environment().getOrDefault("PATH", "/usr/bin:/bin") + ":" + EXTRA_PATH);
        if (isLocal()) pb.directory(new java.io.File(System.getProperty("user.home")));
        return pb.start();
    }

    public boolean exists(String path) {
        return run("test", "-e", path).ok();
    }

    /** Reads a file, escalating to root if the user cannot read it. */
    public String readFile(String path) {
        CmdResult r = run("cat", "--", path);
        if (r.ok()) return r.out();
        if (r.err().contains("Permission denied")) return sudo("cat", "--", path).orThrow();
        throw new CmdException(r.text());
    }

    /** Writes a file as the current user. */
    public void writeFile(String path, String content) {
        exec(List.of("sh", "-c", "cat > \"$1\"", "sh", path), content, DEFAULT_TIMEOUT).orThrow();
    }

    /** Writes a file as root via a private temp file; mode applied when not null. */
    public void writeFileRoot(String path, String content, String mode) {
        if (isRoot()) {
            writeFile(path, content);
            if (mode != null) run("chmod", mode, path).orThrow();
            return;
        }
        String tmp = run("mktemp", "/tmp/spx.XXXXXXXX").orThrow().strip();
        try {
            writeFile(tmp, content);
            sudo("cp", "--", tmp, path).orThrow();
            if (mode != null) sudo("chmod", mode, path).orThrow();
        } finally {
            run("rm", "-f", tmp);
        }
    }

    /** Writes as user if the path is writable, otherwise as root. */
    public void writeFileAuto(String path, String content, String modeIfRoot) {
        boolean writable = exists(path) ? run("test", "-w", path).ok()
                : run("sh", "-c", "test -w \"$(dirname \"$1\")\"", "sh", path).ok();
        if (writable) writeFile(path, content);
        else writeFileRoot(path, content, modeIfRoot);
    }

    // -------------------------------------------------------------- detection

    private static final List<String> KNOWN_TOOLS = List.of("docker", "nginx", "ufw", "nft", "systemctl", "sshd",
            "openssl", "certbot", "fail2ban-client", "crontab", "journalctl", "apt-get", "dnf", "yum", "pacman",
            "zypper", "unattended-upgrade", "tar", "unzip", "zip", "du", "ps", "last", "who", "useradd", "ss",
            "restic", "sensors", "cscli");

    /** Probes installed tools and OS info. Safe to call from a background thread. */
    public void detect() {
        StringBuilder sb = new StringBuilder("for t in");
        for (String t : KNOWN_TOOLS) sb.append(' ').append(t);
        sb.append("; do command -v $t >/dev/null 2>&1 && echo \"T:$t\"; done; cat /etc/os-release 2>/dev/null;");
        sb.append("docker compose version >/dev/null 2>&1 && echo T:compose; true");
        CmdResult r = sh(sb.toString());
        if (r.code() == 255 && !isLocal()) throw new CmdException("SSH connection failed: " + r.text());
        Set<String> t = new HashSet<>();
        Map<String, String> os = new LinkedHashMap<>();
        for (String line : r.lines()) {
            if (line.startsWith("T:")) t.add(line.substring(2));
            else if (line.contains("=")) {
                int i = line.indexOf('=');
                os.put(line.substring(0, i), line.substring(i + 1).replaceAll("^\"|\"$", ""));
            }
        }
        tools = t;
        osRelease = os;
        if (t.contains("apt-get")) pkgManager = "apt";
        else if (t.contains("dnf")) pkgManager = "dnf";
        else if (t.contains("yum")) pkgManager = "yum";
        else if (t.contains("pacman")) pkgManager = "pacman";
        else if (t.contains("zypper")) pkgManager = "zypper";
        else pkgManager = "";
    }

    public boolean has(String tool) { return tools.contains(tool); }
    public String os() { return osRelease.getOrDefault("PRETTY_NAME", "Linux"); }
    public String pkgManager() { return pkgManager; }

    /** argv to install packages with the host's package manager. */
    public List<String> installArgv(String... pkgs) {
        List<String> a = new ArrayList<>();
        switch (pkgManager) {
            case "apt" -> a.addAll(List.of("env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "install", "-y"));
            case "dnf" -> a.addAll(List.of("dnf", "install", "-y"));
            case "yum" -> a.addAll(List.of("yum", "install", "-y"));
            case "pacman" -> a.addAll(List.of("pacman", "-S", "--noconfirm"));
            case "zypper" -> a.addAll(List.of("zypper", "--non-interactive", "install"));
            default -> throw new CmdException("No supported package manager found on " + name);
        }
        a.addAll(Arrays.asList(pkgs));
        return a;
    }

    private static String readAll(InputStream in) {
        try (in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            in.transferTo(bo);
            return bo.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("address", address);
        m.put("user", user);
        m.put("port", port);
        m.put("identity", identityFile);
        return m;
    }

    @Override
    public String toString() { return label(); }
}
