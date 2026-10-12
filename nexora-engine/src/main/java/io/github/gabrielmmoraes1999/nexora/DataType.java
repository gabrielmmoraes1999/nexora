package io.github.gabrielmmoraes1999.nexora;

/**
 * Tipos de coluna persistidos no arquivo da tabela.
 * A ordem dos valores faz parte do formato. Tipos novos entram no final.
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
