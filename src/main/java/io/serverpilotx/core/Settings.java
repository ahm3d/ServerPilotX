package io.serverpilotx.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.prefs.Preferences;

/** User preferences (stored with java.util.prefs under ~/.java). */
public final class Settings {
    public enum Theme { SYSTEM, DARK, LIGHT }
    public enum PrivMethod { SUDO, PKEXEC }

    private static final Preferences P = Preferences.userRoot().node("io/serverpilotx");

    private Settings() {}

    public static Theme theme() {
        try { return Theme.valueOf(P.get("theme", "DARK")); } catch (Exception e) { return Theme.DARK; }
    }
    public static void theme(Theme t) { P.put("theme", t.name()); }

    public static PrivMethod privMethod() {
        try { return PrivMethod.valueOf(P.get("priv", "SUDO")); } catch (Exception e) { return PrivMethod.SUDO; }
    }
    public static void privMethod(PrivMethod m) { P.put("priv", m.name()); }

    public static int sampleSeconds() { return Math.max(1, P.getInt("sampleSeconds", 3)); }
    public static void sampleSeconds(int s) { P.putInt("sampleSeconds", s); }

    public static int retentionDays() { return Math.max(1, P.getInt("retentionDays", 30)); }
    public static void retentionDays(int d) { P.putInt("retentionDays", d); }

    public static String stacksRoot() { return P.get("stacksRoot", "/opt/serverpilotx/stacks"); }
    public static void stacksRoot(String s) { P.put("stacksRoot", s); }

    public static List<String> fileRoots() {
        String raw = P.get("fileRoots", "/opt/serverpilotx,/var/www,/etc/nginx,~");
        List<String> l = new ArrayList<>();
        Arrays.stream(raw.split(",")).map(String::strip).filter(s -> !s.isEmpty()).forEach(l::add);
        return l;
    }
    public static void fileRoots(String csv) { P.put("fileRoots", csv); }

    public static String terminalEmulator() { return P.get("terminal", "auto"); }
    public static void terminalEmulator(String t) { P.put("terminal", t); }

    public static boolean recordTerminal() { return P.getBoolean("recordTerminal", false); }
    public static void recordTerminal(boolean b) { P.putBoolean("recordTerminal", b); }

    public static String serversJson() { return P.get("servers", "[]"); }
    public static void serversJson(String s) { P.put("servers", s); }

    public static String lastServer() { return P.get("lastServer", "local"); }
    public static void lastServer(String id) { P.put("lastServer", id); }

    public static String acmeEmail() { return P.get("acmeEmail", ""); }
    public static void acmeEmail(String e) { P.put("acmeEmail", e); }

    /** ~/.local/share/serverpilotx (XDG data dir). */
    public static Path dataDir() {
        String xdg = System.getenv("XDG_DATA_HOME");
        Path base = (xdg != null && !xdg.isBlank()) ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".local", "share");
        Path p = base.resolve("serverpilotx");
        try { Files.createDirectories(p); } catch (Exception ignored) { }
        return p;
    }

    public static Path cacheDir() {
        Path p = Path.of(System.getProperty("user.home"), ".cache", "serverpilotx");
        try { Files.createDirectories(p); } catch (Exception ignored) { }
        return p;
    }
}
