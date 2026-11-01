package io.serverpilotx.core;

/** Result of a finished command. */
public record CmdResult(int code, String out, String err) {

    public boolean ok() {
        return code == 0;
    }

    /** stdout followed by stderr, trimmed. */
    public String text() {
        String o = out == null ? "" : out;
        String e = err == null ? "" : err;
        if (o.isBlank()) return e.strip();
        if (e.isBlank()) return o.strip();
        return (o.strip() + "\n" + e.strip());
    }

    /** Throws if the command failed, otherwise returns stdout. */
    public String orThrow() {
        if (!ok()) {
            String msg = text();
            throw new CmdException(msg.isBlank() ? "Command failed with exit code " + code : msg);
        }
        return out;
    }

    public String[] lines() {
        if (out == null || out.isBlank()) return new String[0];
        return out.strip().split("\\R");
    }
}
