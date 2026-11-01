package io.serverpilotx.core;

/** A command failed or was cancelled. Message is user-facing. */
public class CmdException extends RuntimeException {
    public CmdException(String message) {
        super(message);
    }

    public CmdException(String message, Throwable cause) {
        super(message, cause);
    }
}
