package io.github.gabrielmmoraes1999.nexora;

/**
 * What happens to the child row when the referenced key changes or disappears.
 * {@code NO ACTION} and {@code RESTRICT} reject the statement while a child exists.
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
            throw new NexException("table schema is corrupt");
        }
        return values[ordinal];
    }
}
