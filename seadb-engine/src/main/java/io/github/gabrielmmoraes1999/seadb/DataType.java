package io.github.gabrielmmoraes1999.seadb;

/**
 * Column types stored in the table file.
 * Value order is part of the format. New types are appended at the end.
 */
public enum DataType {
    INTEGER,
    DOUBLE,
    BOOLEAN,
    CHAR,
    VARCHAR,
    DATE,
    TIMESTAMP,
    DECIMAL,
    BLOB,
    SMALLINT,
    FLOAT,
    TIME,
    BIGINT;

    public boolean exactInteger() {
        return this == SMALLINT || this == INTEGER || this == BIGINT;
    }
}
