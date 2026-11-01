package io.serverpilotx.core;

import java.time.Duration;
import java.util.List;

/**
 * Holds the sudo state for one host. The password is asked once per session through
 * a UI callback, verified, and kept only in memory.
 */
public final class Privilege {
    /** Implemented by the UI: returns the password, or null if the user cancelled. */
    public interface Prompter {
        String ask(Host host, String message);
    }

    private static volatile Prompter prompter = (h, m) -> null;

    private final Host host;
    private Boolean passwordless;
    private String password;

    Privilege(Host host) {
        this.host = host;
    }

    public static void setPrompter(Prompter p) { prompter = p; }

    static boolean isAuthFailure(String err) {
        return err != null && (err.contains("incorrect password") || err.contains("Sorry, try again")
                || err.contains("no password was provided") || err.contains("a password is required"));
    }

    synchronized boolean passwordless() {
        if (passwordless == null) {
            passwordless = host.exec(List.of("sudo", "-n", "true"), null, Duration.ofSeconds(15)).ok();
        }
        return passwordless;
    }

    synchronized String password() {
        if (password != null) return password;
        String msg = "Administrator rights are needed on " + host.name() + ".\nEnter the sudo password for " + host.user() + ".";
        for (int attempt = 0; attempt < 3; attempt++) {
            String pw = prompter.ask(host, msg);
            if (pw == null) throw new CmdException("Cancelled: administrator password required.");
            CmdResult r = host.exec(List.of("sudo", "-S", "-k", "-p", "", "true"), pw + "\n", Duration.ofSeconds(20));
            if (r.ok()) {
                password = pw;
                return pw;
            }
            if (r.err().contains("not in the sudoers") || r.err().contains("may not run sudo")) {
                throw new CmdException(host.user() + " is not allowed to use sudo on " + host.name() + ".");
            }
            msg = "Wrong password, try again.";
        }
        throw new CmdException("Administrator password was rejected 3 times.");
    }

    public synchronized boolean hasPassword() { return password != null || Boolean.TRUE.equals(passwordless); }

    public synchronized void clear() { password = null; }
}
