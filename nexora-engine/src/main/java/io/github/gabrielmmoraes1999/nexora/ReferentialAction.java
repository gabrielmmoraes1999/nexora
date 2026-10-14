package io.github.gabrielmmoraes1999.nexora;

/**
 * O que acontece com a linha filha quando a chave referenciada muda ou some.
 * {@code NO ACTION} e {@code RESTRICT} recusam o comando enquanto existir filho.
 */
public enum ReferentialAction {
    NO_ACTION,
    RESTRICT,
    CASCADE,
    SET_NULL,
    SET_DEFAULT;

    public boolean restricts() {
        return this == NO_ACTION || this == RESTRICT;
    }

    public String sql() {
        return switch (this) {
            case NO_ACTION -> "NO ACTION";
            case RESTRICT -> "RESTRICT";
            case CASCADE -> "CASCADE";
            case SET_NULL -> "SET NULL";
            case SET_DEFAULT -> "SET DEFAULT";
        };
    }

    static ReferentialAction stored(int ordinal) {
        ReferentialAction[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new NexException("esquema da tabela está corrompido");
        }
        return values[ordinal];
    }
}
