package io.github.gabrielmmoraes1999.seadb;

/**
 * SQL, integrity, or table-file read error.
 */
public class SeaException extends RuntimeException {

    public SeaException(String message) {
        super(message);
    }

    public SeaException(String message, Throwable cause) {
        super(message, cause);
    }
}
