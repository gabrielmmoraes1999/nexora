package io.github.gabrielmmoraes1999.nexora.studio;

import java.util.Objects;

/**
 * Explorer item. It does not load Swing: the same value identifies the tab and the menu.
 */
public record Node(Kind kind, String databaseId, String title, String schema, String name) {

    public static final String TABLES = "tables";
    public static final String VIEWS = "views";
    public static final String INDEXES = "indexes";
    public static final String PROCEDURES = "procedures";
    public static final String TRIGGERS = "triggers";
    public static final String GENERATORS = "generators";
    public static final String SCHEMAS = "schemas";
    public static final String ROLES = "roles";
    public static final String USERS = "users";
    public static final String SYSTEM = "system";

    public Node {
        databaseId = databaseId == null ? "" : databaseId;
        title = title == null ? "" : title;
        schema = schema == null ? "" : schema;
        name = name == null ? "" : name;
    }

    public String key() {
        return databaseId + "|" + kind + "|" + schema + "|" + name;
    }

    public boolean sameObject(Node other) {
        return other != null && kind == other.kind && databaseId.equals(other.databaseId)
                && schema.equalsIgnoreCase(other.schema) && name.equalsIgnoreCase(other.name);
    }

    public static Node database(String id, String title) {
        return new Node(Kind.DATABASE, id, title, "", "");
    }

    public static Node folder(String databaseId, String folder, String title) {
        return folder(databaseId, "", folder, title);
    }

    public static Node folder(String databaseId, String schema, String folder, String title) {
        return new Node(Kind.FOLDER, databaseId, title, schema, folder);
    }

    public enum Kind {
        DATABASE,
        FOLDER,
        TABLE,
        SYSTEM_TABLE,
        VIEW,
        INDEX,
        PROCEDURE,
        TRIGGER,
        GENERATOR,
        SCHEMA,
        ROLE,
        USER,
        CONNECTIONS,
        PRIVILEGES,
        CONFIG,
        INFO
    }

    public String place() {
        if (schema.isBlank() || schema.equalsIgnoreCase("public")) {
            return name;
        }
        return schema + "." + name;
    }

    @Override
    public String toString() {
        return title.isBlank() ? Objects.toString(name, "") : title;
    }
}
