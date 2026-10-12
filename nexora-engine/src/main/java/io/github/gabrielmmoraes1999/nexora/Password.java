package io.github.gabrielmmoraes1999.nexora;

/**
 * Senha gravada com bcrypt ({@code $2a$}, custo 10).
 */
final class Password {

    private static final int COST = 10;
    private static final String DUMMY = hash("dba");

    private Password() {
    }

    static String hash(String password) {
        return Bcrypt.hash(COST, password);
    }

    static boolean verify(String stored, String password) {
        if (stored == null || password == null || !bcrypt(stored)) {
            return false;
        }
        try {
            return Bcrypt.verify(password, stored);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    static boolean rejectUnknown(String password) {
        return verify(DUMMY, password);
    }

    private static boolean bcrypt(String stored) {
        return stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$");
    }
}
