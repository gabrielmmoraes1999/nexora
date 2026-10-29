package io.github.gabrielmmoraes1999.seadb;

import java.util.List;

/**
 * Internal tables of the file.
 * {@code SEA$USERS} stores the login. {@code SEA$PRIVILEGES} stores what each user can
 * do on each table. {@code SEA$CONFIG} stores options.
 * {@code SEA$CONNECTIONS} records only open connections. {@code SEA$DATABASE} describes the file.
 * {@code SEA$GENERATORS}, {@code SEA$PROCEDURES}, {@code SEA$TRIGGERS}, {@code SEA$VIEWS},
 * {@code SEA$ROLES}, and {@code SEA$SCHEMAS} store the catalog of generators, procedures,
 * triggers, views, roles, and schemas.
 */
final class SystemTables {

    static final String DATABASE = "SEA$DATABASE";
    static final String CONFIG = "SEA$CONFIG";
    static final String USERS = "SEA$USERS";
    static final String PRIVILEGES = "SEA$PRIVILEGES";
    static final String CONNECTIONS = "SEA$CONNECTIONS";
    static final String GENERATORS = "SEA$GENERATORS";
    static final String PROCEDURES = "SEA$PROCEDURES";
    static final String PROCEDURE_PARAMETERS = "SEA$PROCEDURE_PARAMETERS";
    static final String TRIGGERS = "SEA$TRIGGERS";
    static final String VIEWS = "SEA$VIEWS";
    static final String ROLES = "SEA$ROLES";
    static final String ROLE_MEMBERS = "SEA$ROLE_MEMBERS";
    static final String SCHEMAS = "SEA$SCHEMAS";
    static final String OWNER = "OWNER";
    static final String PUBLIC = "PUBLIC";
    static final List<String> DML = List.of("SELECT", "INSERT", "UPDATE", "DELETE");

    private SystemTables() {
    }

    /** Translate reserved identifiers from databases created before the SeaDB catalog rename. */
    static String currentName(String name) {
        if (name != null && name.regionMatches(true, 0, "NEX$", 0, 4)) {
            return "SEA$" + name.substring(4);
        }
        return name;
    }

    static boolean isSystem(String name) {
        return name != null && Names.fold(name).startsWith("sea$");
    }

    static boolean readOnly(String name) {
        return Names.same(name, DATABASE) || Names.same(name, CONNECTIONS) || Names.same(name, PRIVILEGES)
                || Names.same(name, GENERATORS) || Names.same(name, PROCEDURES)
                || Names.same(name, PROCEDURE_PARAMETERS) || Names.same(name, TRIGGERS)
                || Names.same(name, VIEWS) || Names.same(name, ROLES) || Names.same(name, ROLE_MEMBERS)
                || Names.same(name, SCHEMAS);
    }

    static List<Schema> schemas() {
        return List.of(database(), config(), users(), privileges(), connections(),
                generators(), procedures(), procedureParameters(), triggers(), views(), roles(), roleMembers(),
                schemaCatalog());
    }

    private static Schema database() {
        return new Schema(DATABASE, List.of(
                text("SEA$DATABASE_NAME", 63, true),
                text("SEA$DESCRIPTION", 256, true),
                text("SEA$OWNER_NAME", 63, true),
                column("SEA$CREATED", DataType.TIMESTAMP, 0, true, false, null),
                column("SEA$FORMAT", DataType.INTEGER, 0, true, false, null),
                column("SEA$SQL_DIALECT", DataType.INTEGER, 0, true, false, null),
                column("SEA$PAGE_SIZE", DataType.INTEGER, 0, true, false, null)
        ), new int[]{0}, List.of(), List.of());
    }

    private static Schema config() {
        return new Schema(CONFIG, List.of(
                text("SEA$KEY", 128, true),
                text("SEA$VALUE", 256, true)
        ), new int[]{0}, List.of(), List.of());
    }

    private static Schema users() {
        return new Schema(USERS, List.of(
                text("SEA$USER_NAME", 63, true),
                text("SEA$PASSWORD", 128, true),
                text("SEA$FIRST_NAME", 63, false),
                text("SEA$MIDDLE_NAME", 63, false),
                text("SEA$LAST_NAME", 63, false),
                column("SEA$ACTIVE", DataType.BOOLEAN, 0, true, true, Boolean.TRUE),
                column("SEA$ADMIN", DataType.BOOLEAN, 0, true, true, Boolean.FALSE)
        ), new int[]{0}, List.of(), List.of());
    }

    static Schema privileges() {
        return new Schema(PRIVILEGES, List.of(
                text("SEA$USER_NAME", 63, true),
                text("SEA$TABLE_NAME", 128, true),
                text("SEA$PRIVILEGE", 16, true),
                column("SEA$GRANT_OPTION", DataType.BOOLEAN, 0, true, true, Boolean.FALSE),
                text("SEA$GRANTOR", 63, true)
        ), new int[]{0, 1, 2}, List.of(), List.of());
    }

    private static Schema connections() {
        return new Schema(CONNECTIONS, List.of(
                new Column("SEA$ATTACHMENT_ID", DataType.INTEGER, 0, 0, true, true, 1L, false, null),
                text("SEA$USER_NAME", 63, true),
                column("SEA$CONNECTED_AT", DataType.TIMESTAMP, 0, true, false, null),
                text("SEA$STATE", 16, true),
                text("SEA$REMOTE_PROTOCOL", 16, false),
                text("SEA$REMOTE_ADDRESS", 255, false),
                text("SEA$REMOTE_HOST", 255, false),
                text("SEA$REMOTE_OS_USER", 255, false),
                text("SEA$REMOTE_PROCESS", 255, false)
        ), new int[]{0}, List.of(), List.of());
    }

    private static Schema generators() {
        return new Schema(GENERATORS, List.of(
                text("SEA$SCHEMA_NAME", 128, true),
                text("SEA$GENERATOR_NAME", 63, true),
                column("SEA$GENERATOR_ID", DataType.INTEGER, 0, true, false, null),
                column("SEA$INITIAL_VALUE", DataType.BIGINT, 0, true, false, null),
                column("SEA$INCREMENT", DataType.BIGINT, 0, true, false, null),
                column("SEA$CURRENT_VALUE", DataType.BIGINT, 0, true, false, null),
                text("SEA$OWNER_NAME", 63, true)
        ), new int[]{0, 1}, List.of(), List.of());
    }

    private static Schema procedures() {
        return new Schema(PROCEDURES, List.of(
                text("SEA$SCHEMA_NAME", 128, true),
                text("SEA$PROCEDURE_NAME", 63, true),
                text("SEA$PROCEDURE_SOURCE", Format.MAX_CHAR, true),
                text("SEA$OWNER_NAME", 63, true)
        ), new int[]{0, 1}, List.of(), List.of());
    }

    private static Schema procedureParameters() {
        return new Schema(PROCEDURE_PARAMETERS, List.of(
                text("SEA$SCHEMA_NAME", 128, true),
                text("SEA$PROCEDURE_NAME", 63, true),
                text("SEA$PARAMETER_NAME", 63, true),
                column("SEA$PARAMETER_NUMBER", DataType.INTEGER, 0, true, false, null),
                column("SEA$PARAMETER_TYPE", DataType.SMALLINT, 0, true, false, null),
                text("SEA$FIELD_TYPE", 32, true),
                column("SEA$FIELD_LENGTH", DataType.INTEGER, 0, true, false, null),
                column("SEA$FIELD_SCALE", DataType.INTEGER, 0, true, false, null)
        ), new int[]{0, 1, 4, 3}, List.of(), List.of());
    }

    private static Schema triggers() {
        return new Schema(TRIGGERS, List.of(
                text("SEA$TRIGGER_NAME", 63, true),
                text("SEA$RELATION_NAME", 128, true),
                column("SEA$TRIGGER_SEQUENCE", DataType.INTEGER, 0, true, false, null),
                text("SEA$TRIGGER_TYPE", 64, true),
                text("SEA$TRIGGER_SOURCE", Format.MAX_CHAR, true),
                column("SEA$TRIGGER_INACTIVE", DataType.BOOLEAN, 0, true, true, Boolean.FALSE),
                text("SEA$OWNER_NAME", 63, true)
        ), new int[]{0}, List.of(), List.of());
    }

    private static Schema views() {
        return new Schema(VIEWS, List.of(
                text("SEA$VIEW_NAME", 63, true),
                text("SEA$VIEW_SOURCE", Format.MAX_CHAR, true),
                text("SEA$RELATION_NAME", 128, true),
                text("SEA$COLUMNS", Format.MAX_CHAR, true),
                text("SEA$OWNER_NAME", 63, true)
        ), new int[]{0}, List.of(), List.of());
    }

    private static Schema roles() {
        return new Schema(ROLES, List.of(
                text("SEA$ROLE_NAME", 63, true),
                text("SEA$OWNER_NAME", 63, true)
        ), new int[]{0}, List.of(), List.of());
    }

    private static Schema schemaCatalog() {
        return new Schema(SCHEMAS, List.of(
                text("SEA$SCHEMA_NAME", 128, true),
                text("SEA$OWNER_NAME", 63, true)
        ), new int[]{0}, List.of(), List.of());
    }

    private static Schema roleMembers() {
        return new Schema(ROLE_MEMBERS, List.of(
                text("SEA$ROLE_NAME", 63, true),
                text("SEA$USER_NAME", 63, true),
                column("SEA$ADMIN_OPTION", DataType.BOOLEAN, 0, true, true, Boolean.FALSE),
                text("SEA$GRANTOR", 63, true)
        ), new int[]{0, 1}, List.of(), List.of());
    }

    private static Column text(String name, int length, boolean notNull) {
        return column(name, DataType.VARCHAR, length, notNull, false, null);
    }

    private static Column column(String name, DataType type, int length, boolean notNull, boolean hasDefault, Object defaultValue) {
        return new Column(name, type, length, 0, notNull, false, 0L, hasDefault, defaultValue);
    }
}
