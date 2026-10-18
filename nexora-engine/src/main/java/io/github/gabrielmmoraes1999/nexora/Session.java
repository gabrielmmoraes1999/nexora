package io.github.gabrielmmoraes1999.nexora;

/**
 * An authenticated connection to the {@code .db} file.
 * The user name is stored in uppercase.
 */
public final class Session {

    final String user;
    final boolean admin;
    final long attachmentId;
    final String role;
    private String schemaName = Relation.PUBLIC;
    private boolean closed;

    Session(String user, boolean admin, long attachmentId) {
        this(user, admin, attachmentId, null);
    }

    Session(String user, boolean admin, long attachmentId, String role) {
        this.user = user;
        this.admin = admin;
        this.attachmentId = attachmentId;
        this.role = role;
    }

    public String user() {
        return user;
    }

    public boolean admin() {
        return admin;
    }

    /**
     * Role chosen at login. Without a role, returns {@code NONE}.
     */
    public String role() {
        return role == null ? "NONE" : role;
    }

    /**
     * Session schema. The default is {@code public}.
     */
    public String schema() {
        return schemaName;
    }

    void schema(String schema) {
        this.schemaName = schema;
    }

    /**
     * The session was closed by logout or {@code DROP CONNECTION}.
     */
    public boolean closed() {
        return closed;
    }

    void markClosed() {
        closed = true;
    }
}
