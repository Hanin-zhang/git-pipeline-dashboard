package com.githublook.webhook;

/** A payload that will never succeed on retry (malformed, missing ids) — goes straight to the dead letter list. */
public class PermanentProcessingException extends RuntimeException {

    public PermanentProcessingException(String message) {
        super(message);
    }

    public PermanentProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
