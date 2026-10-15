package io.github.gabrielmmoraes1999.nexora;

/**
 * SQL, integrity, or table-file read error.
 */
public class NexException extends RuntimeException {

    public NexException(String message) {
        super(message);
    }

    public NexException(String message, Throwable cause) {
        super(message, cause);
    }
}
