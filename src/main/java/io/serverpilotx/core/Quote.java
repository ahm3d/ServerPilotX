package io.serverpilotx.core;

import java.util.List;
import java.util.stream.Collectors;

/** POSIX shell quoting helpers. */
public final class Quote {
    private Quote() {}

    public static String sh(String s) {
        if (s == null || s.isEmpty()) return "''";
        if (s.matches("[A-Za-z0-9_./:=@%+,^-]+")) return s;
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    public static String join(List<String> argv) {
        return argv.stream().map(Quote::sh).collect(Collectors.joining(" "));
    }
}
