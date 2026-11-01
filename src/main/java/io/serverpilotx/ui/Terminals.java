package io.serverpilotx.ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.serverpilotx.core.Host;
import io.serverpilotx.core.Quote;
import io.serverpilotx.core.Settings;

/** Opens an interactive command in the desktop's terminal emulator (for full TTY programs). */
public final class Terminals {
    private static final List<String> CANDIDATES = List.of("foot", "kitty", "alacritty", "wezterm", "gnome-terminal",
            "konsole", "xfce4-terminal", "tilix", "ptyxis", "kgx", "x-terminal-emulator", "xterm");

    private Terminals() {}

    public static String detect() {
        String pref = Settings.terminalEmulator();
        if (pref != null && !pref.isBlank() && !pref.equals("auto")) return pref;
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String c : CANDIDATES) {
            for (String dir : path.split(File.pathSeparator)) {
                if (new File(dir, c).canExecute()) return c;
            }
        }
        return null;
    }

    /** Opens an interactive shell (or a command) on the host in a terminal window. */
    public static void open(Host host, String title, String command) {
        List<String> inner;
        if (host.isLocal()) {
            inner = command == null ? List.of(System.getenv().getOrDefault("SHELL", "/bin/bash"))
                    : List.of("sh", "-c", command + "; echo; echo '[process exited — press Enter]'; read _");
        } else {
            inner = host.sshArgv(true, command);
        }
        launch(title, inner);
    }

    public static void launch(String title, List<String> inner) {
        String term = detect();
        if (term == null) {
            Ui.errorDialog("No terminal emulator found",
                    "Install a terminal (foot, kitty, alacritty, gnome-terminal, konsole, xterm…) or set one in Settings.\n\nCommand:\n" + Quote.join(inner));
            return;
        }
        List<String> a = new ArrayList<>();
        a.add(term);
        String base = new File(term).getName();
        switch (base) {
            case "gnome-terminal", "ptyxis", "kgx" -> { a.add("--title=" + title); a.add("--"); }
            case "kitty" -> { a.add("--title"); a.add(title); }
            case "foot" -> { a.add("--title=" + title); }
            case "wezterm" -> { a.add("start"); a.add("--"); }
            case "alacritty" -> { a.add("--title"); a.add(title); a.add("-e"); }
            case "xterm", "konsole", "xfce4-terminal", "tilix", "x-terminal-emulator" -> a.add("-e");
            default -> a.add("-e");
        }
        if (base.equals("xfce4-terminal") || base.equals("tilix")) {
            a.add(Quote.join(inner));
        } else {
            a.addAll(inner);
        }
        try {
            new ProcessBuilder(a).inheritIO().start();
        } catch (Exception e) {
            Ui.errorDialog("Cannot start terminal", e.getMessage());
        }
    }
}
