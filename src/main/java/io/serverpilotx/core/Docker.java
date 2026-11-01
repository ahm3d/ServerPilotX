package io.serverpilotx.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Runs the docker CLI on a host, using sudo only when the user is not in the docker group. */
public final class Docker {
    private static final Map<String, Boolean> NEEDS_ROOT = new ConcurrentHashMap<>();

    private Docker() {}

    public static boolean needsRoot(Host h) {
        return NEEDS_ROOT.computeIfAbsent(h.id(), k -> {
            CmdResult r = h.run("docker", "version", "--format", "{{.Server.Version}}");
            return !r.ok() && r.text().toLowerCase().contains("permission denied");
        });
    }

    /** Is the Docker daemon reachable? Returns null when OK, otherwise a reason. */
    public static String problem(Host h) {
        if (!h.has("docker")) return "Docker is not installed on " + h.name() + ".";
        CmdResult r = run(h, "version", "--format", "{{.Server.Version}}");
        if (!r.ok()) return "Docker daemon is not reachable: " + r.text();
        return null;
    }

    public static CmdResult run(Host h, String... args) {
        List<String> a = new ArrayList<>();
        a.add("docker");
        a.addAll(Arrays.asList(args));
        return needsRoot(h) ? h.sudo(a, Host.DEFAULT_TIMEOUT) : h.exec(a, null, Host.DEFAULT_TIMEOUT);
    }

    public static List<String> argv(String... args) {
        List<String> a = new ArrayList<>();
        a.add("docker");
        a.addAll(Arrays.asList(args));
        return a;
    }

    public static List<Map<String, Object>> json(Host h, String... args) {
        return Json.lines(run(h, args).orThrow());
    }

    /** Masks values of environment variables that look like secrets. */
    public static String maskSecrets(String text) {
        return text.replaceAll("(?i)(\"[A-Z0-9_]*(PASS|PASSWORD|SECRET|TOKEN|KEY|PRIVATE)[A-Z0-9_]*=)[^\"]*\"", "$1••••••\"");
    }
}
