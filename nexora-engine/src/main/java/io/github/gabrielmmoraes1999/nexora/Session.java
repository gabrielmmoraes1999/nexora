package io.github.gabrielmmoraes1999.nexora;

/**
 * Uma conexão autenticada no arquivo {@code .db}.
 * O nome do usuário fica em maiúsculas, como no Firebird.
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
     * Papel escolhido no login. Sem papel, devolve {@code NONE}.
     */
    public String role() {
        return role == null ? "NONE" : role;
    }

    /**
     * Esquema da sessão. O padrão é {@code public}.
     */
    public String schema() {
        return schemaName;
    }

    void schema(String schema) {
        this.schemaName = schema;
    }

    /**
     * A sessão foi encerrada com logout ou {@code DROP CONNECTION}.
     */
    public boolean closed() {
        return closed;
    }

    void markClosed() {
        closed = true;
    }
}
