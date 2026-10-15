package io.github.gabrielmmoraes1999.nexora;

import java.util.List;

/**
 * Tabelas internas do arquivo, no papel das {@code RDB$} e {@code MON$} do Firebird.
 * {@code NEX$USERS} guarda o login. {@code NEX$PRIVILEGES} guarda o que cada um pode
 * fazer em cada tabela. {@code NEX$CONFIG} guarda opções.
 * {@code NEX$CONNECTIONS} registra só as conexões abertas. {@code NEX$DATABASE} descreve o arquivo.
 * {@code NEX$GENERATORS}, {@code NEX$PROCEDURES}, {@code NEX$TRIGGERS}, {@code NEX$VIEWS},
 * {@code NEX$ROLES} e {@code NEX$SCHEMAS} guardam o catálogo de geradores, procedures,
 * triggers, views, papéis e esquemas.
 */
final class SystemTables {

    static final String DATABASE = "NEX$DATABASE";
    static final String CONFIG = "NEX$CONFIG";
    static final String USERS = "NEX$USERS";
    static final String PRIVILEGES = "NEX$PRIVILEGES";
    static final String CONNECTIONS = "NEX$CONNECTIONS";
    static final String GENERATORS = "NEX$GENERATORS";
    static final String PROCEDURES = "NEX$PROCEDURES";
    static final String PROCEDURE_PARAMETERS = "NEX$PROCEDURE_PARAMETERS";
    static final String TRIGGERS = "NEX$TRIGGERS";
    static final String VIEWS = "NEX$VIEWS";
    static final String ROLES = "NEX$ROLES";
    static final String ROLE_MEMBERS = "NEX$ROLE_MEMBERS";
    static final String SCHEMAS = "NEX$SCHEMAS";
    static final String OWNER = "OWNER";
    static final String PUBLIC = "PUBLIC";
    static final List<String> DML = List.of("SELECT", "INSERT", "UPDATE", "DELETE");

    private SystemTables() {
    }

    static boolean isSystem(String name) {
        return name != null && Names.fold(name).startsWith("nex$");
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
                text("NEX$DATABASE_NAME", 63, true),
                text("NEX$DESCRIPTION", 256, true),
                text("NEX$OWNER_NAME", 63, true),
                column("NEX$CREATED", DataType.TIMESTAMP, 0, true, false, null),
                column("NEX$FORMAT", DataType.INTEGER, 0, true, false, null),
                column("NEX$SQL_DIALECT", DataType.INTEGER, 0, true, false, null),
                column("NEX$PAGE_SIZE", DataType.INTEGER, 0, true, false, null)
        ), new int[]{0}, List.of(), List.of());
    }

    private static Schema config() {
        return new Schema(CONFIG, List.of(
                text("NEX$KEY", 128, true),
                text("NEX$VALUE", 256, true)
        ), new int[]{0}, List.of(), List.of());
    }

    private static Schema users() {
        return new Schema(USERS, List.of(
                text("NEX$USER_NAME", 63, true),
                text("NEX$PASSWORD", 128, true),
                text("NEX$FIRST_NAME", 63, false),
                text("NEX$MIDDLE_NAME", 63, false),
                text("NEX$LAST_NAME", 63, false),
                column("NEX$ACTIVE", DataType.BOOLEAN, 0, true, true, Boolean.TRUE),
                column("NEX$ADMIN", DataType.BOOLEAN, 0, true, true, Boolean.FALSE)
        ), new int[]{0}, List.of(), List.of());
    }

    static Schema privileges() {
        return new Schema(PRIVILEGES, List.of(
                text("NEX$USER_NAME", 63, true),
                text("NEX$TABLE_NAME", 128, true),
                text("NEX$PRIVILEGE", 16, true),
                column("NEX$GRANT_OPTION", DataType.BOOLEAN, 0, true, true, Boolean.FALSE),
                text("NEX$GRANTOR", 63, true)
        ), new int[]{0, 1, 2}, List.of(), List.of());
    }

    static Schema connectionsSchema() {
        return connections();
    }

    private static Schema connections() {
        return new Schema(CONNECTIONS, List.of(
                new Column("NEX$ATTACHMENT_ID", DataType.INTEGER, 0, 0, true, true, 1L, false, null),
                text("NEX$USER_NAME", 63, true),
                column("NEX$CONNECTED_AT", DataType.TIMESTAMP, 0, true, false, null),
                text("NEX$STATE", 16, true),
                text("NEX$REMOTE_PROTOCOL", 16, false),
                text("NEX$REMOTE_ADDRESS", 255, false),
                text("NEX$REMOTE_HOST", 255, false),
                text("NEX$REMOTE_OS_USER", 255, false),
                text("NEX$REMOTE_PROCESS", 255, false)
        ), new int[]{0}, List.of(), List.of());
    }

    private static Schema generators() {
        return new Schema(GENERATORS, List.of(
                text("NEX$GENERATOR_NAME", 63, true),
                column("NEX$GENERATOR_ID", DataType.INTEGER, 0, true, false, null),
                column("NEX$INITIAL_VALUE", DataType.BIGINT, 0, true, false, null),
                column("NEX$INCREMENT", DataType.BIGINT, 0, true, false, null),
                column("NEX$CURRENT_VALUE", DataType.BIGINT, 0, true, false, null),
                text("NEX$OWNER_NAME", 63, true)
        ), new int[]{0}, List.of(), List.of());
    }

    private static Schema procedures() {
        return new Schema(PROCEDURES, List.of(
                text("NEX$SCHEMA_NAME", 128, true),
                text("NEX$PROCEDURE_NAME", 63, true),
                text("NEX$PROCEDURE_SOURCE", Format.MAX_CHAR, true),
                text("NEX$OWNER_NAME", 63, true)
        ), new int[]{0, 1}, List.of(), List.of());
    }

    private static Schema procedureParameters() {
        return new Schema(PROCEDURE_PARAMETERS, List.of(
                text("NEX$SCHEMA_NAME", 128, true),
                text("NEX$PROCEDURE_NAME", 63, true),
                text("NEX$PARAMETER_NAME", 63, true),
                column("NEX$PARAMETER_NUMBER", DataType.INTEGER, 0, true, false, null),
                column("NEX$PARAMETER_TYPE", DataType.SMALLINT, 0, true, false, null),
                text("NEX$FIELD_TYPE", 32, true),
                column("NEX$FIELD_LENGTH", DataType.INTEGER, 0, true, false, null),
                column("NEX$FIELD_SCALE", DataType.INTEGER, 0, true, false, null)
        ), new int[]{0, 1, 4, 3}, List.of(), List.of());
    }

    private static Schema triggers() {
        return new Schema(TRIGGERS, List.of(
                text("NEX$TRIGGER_NAME", 63, true),
                text("NEX$RELATION_NAME", 128, true),
                column("NEX$TRIGGER_SEQUENCE", DataType.INTEGER, 0, true, false, null),
                text("NEX$TRIGGER_TYPE", 64, true),
                text("NEX$TRIGGER_SOURCE", Format.MAX_CHAR, true),
                column("NEX$TRIGGER_INACTIVE", DataType.BOOLEAN, 0, true, true, Boolean.FALSE),
                text("NEX$OWNER_NAME", 63, true)
        ), new int[]{0}, List.of(), List.of());
    }

    private static Schema views() {
        return new Schema(VIEWS, List.of(
                text("NEX$VIEW_NAME", 63, true),
                text("NEX$VIEW_SOURCE", Format.MAX_CHAR, true),
                text("NEX$RELATION_NAME", 128, true),
                text("NEX$COLUMNS", Format.MAX_CHAR, true),
                text("NEX$OWNER_NAME", 63, true)
        ), new int[]{0}, List.of(), List.of());
    }

    private static Schema roles() {
        return new Schema(ROLES, List.of(
                text("NEX$ROLE_NAME", 63, true),
                text("NEX$OWNER_NAME", 63, true)
        ), new int[]{0}, List.of(), List.of());
    }

    private static Schema schemaCatalog() {
        return new Schema(SCHEMAS, List.of(
                text("NEX$SCHEMA_NAME", 128, true),
                text("NEX$OWNER_NAME", 63, true)
        ), new int[]{0}, List.of(), List.of());
    }

    private static Schema roleMembers() {
        return new Schema(ROLE_MEMBERS, List.of(
                text("NEX$ROLE_NAME", 63, true),
                text("NEX$USER_NAME", 63, true),
                column("NEX$ADMIN_OPTION", DataType.BOOLEAN, 0, true, true, Boolean.FALSE),
                text("NEX$GRANTOR", 63, true)
        ), new int[]{0, 1}, List.of(), List.of());
    }

    private static Column text(String name, int length, boolean notNull) {
        return column(name, DataType.VARCHAR, length, notNull, false, null);
    }

    private static Column column(String name, DataType type, int length, boolean notNull, boolean hasDefault, Object defaultValue) {
        return new Column(name, type, length, 0, notNull, false, 0L, hasDefault, defaultValue);
    }
}
