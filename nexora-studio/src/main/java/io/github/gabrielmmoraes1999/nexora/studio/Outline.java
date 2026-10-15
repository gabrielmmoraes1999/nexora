package io.github.gabrielmmoraes1999.nexora.studio;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fotografia do catálogo e leituras pontuais das tabelas {@code NEX$}.
 * Uma falha de permissão numa tabela de sistema não esconde as tabelas do usuário.
 */
public record Outline(
        List<RelationName> tables,
        List<RelationName> systemTables,
        List<RelationName> views,
        List<RelationName> procedures,
        List<TriggerRef> triggers,
        List<GeneratorRef> generators,
        List<String> schemas,
        List<String> roles,
        List<String> users,
        List<String> warnings
) {

    public Outline {
        tables = List.copyOf(tables);
        systemTables = List.copyOf(systemTables);
        views = List.copyOf(views);
        procedures = List.copyOf(procedures);
        triggers = List.copyOf(triggers);
        generators = List.copyOf(generators);
        schemas = List.copyOf(schemas);
        roles = List.copyOf(roles);
        users = List.copyOf(users);
        warnings = List.copyOf(warnings);
    }

    public record RelationName(String schema, String name, String type) {
    }

    public record TriggerRef(String name, String relation, String type, boolean inactive) {
    }

    public record GeneratorRef(String name, long current) {
    }

    public record TriggerInfo(String name, String relation, int position, String type, String source,
                              boolean inactive, String owner) {
    }

    public record GeneratorInfo(String name, long initial, long increment, long current, String owner) {
    }

    public record Routine(String name, String owner, String source, List<Ddl.Field> parameters) {
        public Routine {
            parameters = List.copyOf(parameters);
        }
    }

    public record ViewInfo(String schema, String name, String source, String relation, List<String> columns, String owner) {
        public ViewInfo {
            columns = List.copyOf(columns);
        }
    }

    public record GrantInfo(String user, String table, String privilege, boolean grantOption, String grantor) {
    }

    public record UserInfo(String name, String first, String middle, String last, boolean active, boolean admin) {
    }

    public record LinkInfo(long id, String user, String state, String connectedAt, String protocol,
                           String address, String host, String osUser, String process) {
    }

    public record ConfigInfo(String key, String value) {
    }

    public record DatabaseInfo(String name, String description, String owner, String created, int format, int dialect, int pageSize) {
    }

    public record MemberInfo(String role, String user, boolean adminOption, String grantor) {
    }

    public static Outline load(Connection connection) throws SQLException {
        List<RelationName> tables = new ArrayList<>();
        List<RelationName> systemTables = new ArrayList<>();
        List<RelationName> views = new ArrayList<>();
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet result = meta.getTables(null, null, "%", new String[]{"TABLE", "VIEW", "SYSTEM TABLE"})) {
            while (result.next()) {
                String schema = text(result.getString("TABLE_SCHEM"));
                String name = result.getString("TABLE_NAME");
                String type = result.getString("TABLE_TYPE");
                if (name == null) {
                    continue;
                }
                RelationName relation = new RelationName(schema.isBlank() ? "public" : schema, name, type);
                if ("VIEW".equalsIgnoreCase(type)) {
                    views.add(relation);
                } else if ("SYSTEM TABLE".equalsIgnoreCase(type)) {
                    systemTables.add(relation);
                } else {
                    tables.add(relation);
                }
            }
        }
        Comparator<RelationName> order = Comparator
                .comparing(RelationName::schema, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(RelationName::name, String.CASE_INSENSITIVE_ORDER);
        tables.sort(order);
        systemTables.sort(order);
        views.sort(order);

        List<String> warnings = new ArrayList<>();
        List<String> schemas = new ArrayList<>();
        try (ResultSet result = meta.getSchemas()) {
            while (result.next()) {
                String schema = result.getString("TABLE_SCHEM");
                if (schema != null && !schema.isBlank()) {
                    schemas.add(schema);
                }
            }
        }
        schemas.sort(Outline::schemaOrder);

        List<RelationName> procedures = new ArrayList<>();
        for (Row row : tryQuery(connection, warnings, "procedures",
                "SELECT NEX$SCHEMA_NAME, NEX$PROCEDURE_NAME FROM NEX$PROCEDURES")) {
            String schema = row.text("NEX$SCHEMA_NAME");
            String name = row.text("NEX$PROCEDURE_NAME");
            if (schema == null || schema.isBlank() || name == null || name.isBlank()) {
                continue;
            }
            procedures.add(new RelationName(schema, name, "PROCEDURE"));
        }
        procedures.sort(order);
        List<TriggerRef> triggers = new ArrayList<>();
        for (Row row : tryQuery(connection, warnings, "triggers", """
                SELECT NEX$TRIGGER_NAME, NEX$RELATION_NAME, NEX$TRIGGER_TYPE, NEX$TRIGGER_INACTIVE
                FROM NEX$TRIGGERS
                """)) {
            triggers.add(new TriggerRef(row.text("NEX$TRIGGER_NAME"), row.text("NEX$RELATION_NAME"),
                    row.text("NEX$TRIGGER_TYPE"), row.bool("NEX$TRIGGER_INACTIVE")));
        }
        triggers.sort(Comparator.comparing(TriggerRef::name, String.CASE_INSENSITIVE_ORDER));
        List<GeneratorRef> generators = new ArrayList<>();
        for (Row row : tryQuery(connection, warnings, "geradores",
                "SELECT NEX$GENERATOR_NAME, NEX$CURRENT_VALUE FROM NEX$GENERATORS")) {
            generators.add(new GeneratorRef(row.text("NEX$GENERATOR_NAME"), row.number("NEX$CURRENT_VALUE")));
        }
        generators.sort(Comparator.comparing(GeneratorRef::name, String.CASE_INSENSITIVE_ORDER));
        List<String> roles = names(tryQuery(connection, warnings, "papéis",
                "SELECT NEX$ROLE_NAME FROM NEX$ROLES"), "NEX$ROLE_NAME");
        List<String> users = names(tryQuery(connection, warnings, "usuários",
                "SELECT NEX$USER_NAME FROM NEX$USERS"), "NEX$USER_NAME");
        return new Outline(tables, systemTables, views, procedures, triggers, generators, schemas, roles, users, warnings);
    }

    public static List<Ddl.ColumnInfo> columns(Connection connection, String schema, String table) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        Map<String, Integer> keys = new LinkedHashMap<>();
        try (ResultSet result = meta.getPrimaryKeys(null, SqlNames.likeExact(schema), SqlNames.likeExact(table))) {
            while (result.next()) {
                if (!SqlNames.same(table, result.getString("TABLE_NAME"))) {
                    continue;
                }
                keys.put(result.getString("COLUMN_NAME"), (int) result.getShort("KEY_SEQ"));
            }
        }
        List<Ddl.ColumnInfo> columns = new ArrayList<>();
        try (ResultSet result = meta.getColumns(null, SqlNames.likeExact(schema), SqlNames.likeExact(table), "%")) {
            while (result.next()) {
                if (!SqlNames.same(table, result.getString("TABLE_NAME"))) {
                    continue;
                }
                String name = result.getString("COLUMN_NAME");
                int digits = result.getInt("DECIMAL_DIGITS");
                columns.add(new Ddl.ColumnInfo(
                        name,
                        result.getString("TYPE_NAME"),
                        result.getInt("COLUMN_SIZE"),
                        digits,
                        "YES".equalsIgnoreCase(result.getString("IS_NULLABLE")),
                        result.getString("COLUMN_DEF"),
                        "YES".equalsIgnoreCase(result.getString("IS_AUTOINCREMENT")),
                        keys.getOrDefault(name, 0)));
            }
        }
        return List.copyOf(columns);
    }

    public static List<Ddl.IndexInfo> indexes(Connection connection, String schema, String table) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        Map<String, List<IndexColumn>> grouped = new LinkedHashMap<>();
        Map<String, Boolean> unique = new LinkedHashMap<>();
        Map<String, Boolean> descending = new LinkedHashMap<>();
        try (ResultSet result = meta.getIndexInfo(null, SqlNames.likeExact(schema), SqlNames.likeExact(table), false, false)) {
            while (result.next()) {
                if (!SqlNames.same(table, result.getString("TABLE_NAME"))) {
                    continue;
                }
                String name = result.getString("INDEX_NAME");
                String column = result.getString("COLUMN_NAME");
                if (name == null || column == null) {
                    continue;
                }
                unique.putIfAbsent(name, !result.getBoolean("NON_UNIQUE"));
                String order = result.getString("ASC_OR_DESC");
                if ("D".equalsIgnoreCase(order)) {
                    descending.put(name, true);
                } else {
                    descending.putIfAbsent(name, false);
                }
                grouped.computeIfAbsent(name, key -> new ArrayList<>())
                        .add(new IndexColumn(result.getShort("ORDINAL_POSITION"), column));
            }
        }
        List<Ddl.IndexInfo> indexes = new ArrayList<>();
        for (Map.Entry<String, List<IndexColumn>> entry : grouped.entrySet()) {
            List<IndexColumn> parts = entry.getValue();
            parts.sort(Comparator.comparingInt(IndexColumn::position));
            List<String> names = new ArrayList<>();
            for (IndexColumn part : parts) {
                names.add(part.name);
            }
            indexes.add(new Ddl.IndexInfo(entry.getKey(), unique.getOrDefault(entry.getKey(), false), names,
                    descending.getOrDefault(entry.getKey(), false)));
        }
        indexes.sort(Comparator.comparing(Ddl.IndexInfo::name, String.CASE_INSENSITIVE_ORDER));
        return indexes;
    }

    public static List<Ddl.ConstraintInfo> primaryKeys(Connection connection, String schema, String table,
                                                       List<Ddl.IndexInfo> indexes) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        Map<Integer, String> columns = new java.util.TreeMap<>();
        String name = null;
        try (ResultSet result = meta.getPrimaryKeys(null, SqlNames.likeExact(schema), SqlNames.likeExact(table))) {
            while (result.next()) {
                if (!SqlNames.same(table, result.getString("TABLE_NAME"))) {
                    continue;
                }
                columns.put((int) result.getShort("KEY_SEQ"), result.getString("COLUMN_NAME"));
                name = result.getString("PK_NAME");
            }
        }
        if (columns.isEmpty()) {
            return List.of();
        }
        List<String> fields = new ArrayList<>(columns.values());
        Ddl.IndexInfo index = matchIndex(indexes, name, fields, true);
        return List.of(new Ddl.ConstraintInfo(
                name == null ? "PK_" + table : name,
                fields,
                index == null ? (name == null ? "PK_" + table : name) : index.name(),
                index != null && index.descending(),
                null,
                null,
                List.of(),
                null,
                null));
    }

    public static List<Ddl.ConstraintInfo> foreignKeys(Connection connection, String schema, String table,
                                                       List<Ddl.IndexInfo> indexes) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        Map<String, List<ForeignColumn>> grouped = new LinkedHashMap<>();
        try (ResultSet result = meta.getImportedKeys(null, SqlNames.likeExact(schema), SqlNames.likeExact(table))) {
            while (result.next()) {
                if (!SqlNames.same(table, result.getString("FKTABLE_NAME"))) {
                    continue;
                }
                String name = result.getString("FK_NAME");
                grouped.computeIfAbsent(name, key -> new ArrayList<>()).add(new ForeignColumn(
                        result.getShort("KEY_SEQ"),
                        result.getString("FKCOLUMN_NAME"),
                        result.getString("PKTABLE_SCHEM"),
                        result.getString("PKTABLE_NAME"),
                        result.getString("PKCOLUMN_NAME"),
                        result.getShort("UPDATE_RULE"),
                        result.getShort("DELETE_RULE")));
            }
        }
        List<Ddl.ConstraintInfo> keys = new ArrayList<>();
        for (Map.Entry<String, List<ForeignColumn>> entry : grouped.entrySet()) {
            List<ForeignColumn> parts = entry.getValue();
            parts.sort(Comparator.comparingInt(ForeignColumn::sequence));
            List<String> fields = new ArrayList<>();
            List<String> referenced = new ArrayList<>();
            for (ForeignColumn part : parts) {
                fields.add(part.column);
                referenced.add(part.referencedColumn);
            }
            ForeignColumn first = parts.get(0);
            Ddl.IndexInfo index = matchIndex(indexes, entry.getKey(), fields, false);
            keys.add(new Ddl.ConstraintInfo(
                    entry.getKey(),
                    fields,
                    index == null ? entry.getKey() : index.name(),
                    index != null && index.descending(),
                    first.referencedSchema,
                    first.referencedTable,
                    referenced,
                    ruleText(first.updateRule),
                    ruleText(first.deleteRule)));
        }
        keys.sort(Comparator.comparing(Ddl.ConstraintInfo::name, String.CASE_INSENSITIVE_ORDER));
        return keys;
    }

    public static List<Ddl.ConstraintInfo> uniques(List<Ddl.IndexInfo> indexes, List<Ddl.ConstraintInfo> primary) {
        List<String> primaryColumns = primary.isEmpty() ? List.of() : primary.get(0).columns();
        String primaryIndex = primary.isEmpty() ? "" : primary.get(0).index();
        List<Ddl.ConstraintInfo> keys = new ArrayList<>();
        for (Ddl.IndexInfo index : indexes) {
            if (!index.unique() || index.name().equalsIgnoreCase(primaryIndex) || sameColumns(index.columns(), primaryColumns)) {
                continue;
            }
            keys.add(new Ddl.ConstraintInfo(index.name(), index.columns(), index.name(), index.descending(),
                    null, null, List.of(), null, null));
        }
        return keys;
    }

    private static Ddl.IndexInfo matchIndex(List<Ddl.IndexInfo> indexes, String preferred, List<String> columns, boolean unique) {
        Ddl.IndexInfo fallback = null;
        for (Ddl.IndexInfo index : indexes) {
            if (unique && !index.unique()) {
                continue;
            }
            if (!sameColumns(index.columns(), columns)) {
                continue;
            }
            if (preferred != null && index.name().equalsIgnoreCase(preferred)) {
                return index;
            }
            if (fallback == null) {
                fallback = index;
            }
        }
        return fallback;
    }

    private static boolean sameColumns(List<String> left, List<String> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!left.get(i).equalsIgnoreCase(right.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static String ruleText(short code) {
        if (code == DatabaseMetaData.importedKeyCascade) {
            return "CASCADE";
        }
        if (code == DatabaseMetaData.importedKeySetNull) {
            return "SET NULL";
        }
        if (code == DatabaseMetaData.importedKeySetDefault) {
            return "SET DEFAULT";
        }
        if (code == DatabaseMetaData.importedKeyRestrict) {
            return "RESTRICT";
        }
        return "NO ACTION";
    }

    private record ForeignColumn(int sequence, String column, String referencedSchema, String referencedTable,
                                 String referencedColumn, short updateRule, short deleteRule) {
    }

    public static List<TriggerInfo> triggers(Connection connection) throws SQLException {
        List<TriggerInfo> list = new ArrayList<>();
        for (Row row : query(connection, """
                SELECT NEX$TRIGGER_NAME, NEX$RELATION_NAME, NEX$TRIGGER_SEQUENCE, NEX$TRIGGER_TYPE,
                       NEX$TRIGGER_SOURCE, NEX$TRIGGER_INACTIVE, NEX$OWNER_NAME
                FROM NEX$TRIGGERS
                """)) {
            list.add(new TriggerInfo(
                    row.text("NEX$TRIGGER_NAME"),
                    row.text("NEX$RELATION_NAME"),
                    (int) row.number("NEX$TRIGGER_SEQUENCE"),
                    row.text("NEX$TRIGGER_TYPE"),
                    row.text("NEX$TRIGGER_SOURCE"),
                    row.bool("NEX$TRIGGER_INACTIVE"),
                    row.text("NEX$OWNER_NAME")));
        }
        list.sort(Comparator.comparing(TriggerInfo::relation, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(TriggerInfo::position)
                .thenComparing(TriggerInfo::name, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    public static Routine procedure(Connection connection, String schema, String name) throws SQLException {
        Routine found = null;
        for (Row row : query(connection, """
                SELECT NEX$SCHEMA_NAME, NEX$PROCEDURE_NAME, NEX$PROCEDURE_SOURCE, NEX$OWNER_NAME
                FROM NEX$PROCEDURES
                """)) {
            if (!SqlNames.same(schema, row.text("NEX$SCHEMA_NAME")) || !SqlNames.same(name, row.text("NEX$PROCEDURE_NAME"))) {
                continue;
            }
            found = new Routine(row.text("NEX$PROCEDURE_NAME"), row.text("NEX$OWNER_NAME"),
                    row.text("NEX$PROCEDURE_SOURCE"), List.of());
            break;
        }
        if (found == null) {
            throw new SQLException("procedure não encontrada: " + name);
        }
        List<Parameter> parameters = new ArrayList<>();
        for (Row row : query(connection, """
                SELECT NEX$SCHEMA_NAME, NEX$PROCEDURE_NAME, NEX$PARAMETER_NAME, NEX$PARAMETER_NUMBER, NEX$PARAMETER_TYPE,
                       NEX$FIELD_TYPE, NEX$FIELD_LENGTH, NEX$FIELD_SCALE
                FROM NEX$PROCEDURE_PARAMETERS
                """)) {
            if (!SqlNames.same(schema, row.text("NEX$SCHEMA_NAME")) || !SqlNames.same(name, row.text("NEX$PROCEDURE_NAME"))) {
                continue;
            }
            parameters.add(new Parameter(
                    (int) row.number("NEX$PARAMETER_TYPE"),
                    (int) row.number("NEX$PARAMETER_NUMBER"),
                    new Ddl.Field(
                            row.text("NEX$PARAMETER_NAME"),
                            row.text("NEX$FIELD_TYPE"),
                            (int) row.number("NEX$FIELD_LENGTH"),
                            (int) row.number("NEX$FIELD_SCALE"),
                            row.number("NEX$PARAMETER_TYPE") == 0)));
        }
        parameters.sort(Comparator.comparingInt(Parameter::direction).thenComparingInt(Parameter::number));
        List<Ddl.Field> fields = new ArrayList<>();
        for (Parameter parameter : parameters) {
            fields.add(parameter.field);
        }
        return new Routine(found.name, found.owner, found.source, fields);
    }

    public static ViewInfo view(Connection connection, String schema, String name) throws SQLException {
        String stored = SqlNames.catalog(schema, name);
        for (Row row : query(connection, """
                SELECT NEX$VIEW_NAME, NEX$VIEW_SOURCE, NEX$RELATION_NAME, NEX$COLUMNS, NEX$OWNER_NAME
                FROM NEX$VIEWS
                """)) {
            String viewName = row.text("NEX$VIEW_NAME");
            if (!SqlNames.same(stored, viewName) && !SqlNames.same(name, viewName)) {
                continue;
            }
            return new ViewInfo(schema, name, row.text("NEX$VIEW_SOURCE"), row.text("NEX$RELATION_NAME"),
                    splitColumns(row.text("NEX$COLUMNS")), row.text("NEX$OWNER_NAME"));
        }
        throw new SQLException("view não encontrada: " + name);
    }

    public static GeneratorInfo generator(Connection connection, String name) throws SQLException {
        for (Row row : query(connection, """
                SELECT NEX$GENERATOR_NAME, NEX$INITIAL_VALUE, NEX$INCREMENT, NEX$CURRENT_VALUE, NEX$OWNER_NAME
                FROM NEX$GENERATORS
                """)) {
            if (SqlNames.same(name, row.text("NEX$GENERATOR_NAME"))) {
                return new GeneratorInfo(row.text("NEX$GENERATOR_NAME"), row.number("NEX$INITIAL_VALUE"),
                        row.number("NEX$INCREMENT"), row.number("NEX$CURRENT_VALUE"), row.text("NEX$OWNER_NAME"));
            }
        }
        throw new SQLException("gerador não encontrado: " + name);
    }

    public static List<GrantInfo> grants(Connection connection) throws SQLException {
        List<GrantInfo> list = new ArrayList<>();
        for (Row row : query(connection, """
                SELECT NEX$USER_NAME, NEX$TABLE_NAME, NEX$PRIVILEGE, NEX$GRANT_OPTION, NEX$GRANTOR
                FROM NEX$PRIVILEGES
                """)) {
            list.add(new GrantInfo(row.text("NEX$USER_NAME"), row.text("NEX$TABLE_NAME"), row.text("NEX$PRIVILEGE"),
                    row.bool("NEX$GRANT_OPTION"), row.text("NEX$GRANTOR")));
        }
        list.sort(Comparator.comparing(GrantInfo::table, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(GrantInfo::user, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(GrantInfo::privilege));
        return list;
    }

    public static List<UserInfo> users(Connection connection) throws SQLException {
        List<UserInfo> list = new ArrayList<>();
        for (Row row : query(connection, """
                SELECT NEX$USER_NAME, NEX$FIRST_NAME, NEX$MIDDLE_NAME, NEX$LAST_NAME, NEX$ACTIVE, NEX$ADMIN
                FROM NEX$USERS
                """)) {
            list.add(new UserInfo(row.text("NEX$USER_NAME"), row.text("NEX$FIRST_NAME"), row.text("NEX$MIDDLE_NAME"),
                    row.text("NEX$LAST_NAME"), row.bool("NEX$ACTIVE"), row.bool("NEX$ADMIN")));
        }
        list.sort(Comparator.comparing(UserInfo::name, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    public static List<LinkInfo> connections(Connection connection) throws SQLException {
        List<LinkInfo> list = new ArrayList<>();
        for (Row row : query(connection, """
                SELECT NEX$ATTACHMENT_ID, NEX$USER_NAME, NEX$STATE, NEX$CONNECTED_AT,
                       NEX$REMOTE_PROTOCOL, NEX$REMOTE_ADDRESS, NEX$REMOTE_HOST, NEX$REMOTE_OS_USER, NEX$REMOTE_PROCESS
                FROM NEX$CONNECTIONS
                """)) {
            list.add(new LinkInfo(row.number("NEX$ATTACHMENT_ID"), row.text("NEX$USER_NAME"), row.text("NEX$STATE"),
                    row.text("NEX$CONNECTED_AT"), row.text("NEX$REMOTE_PROTOCOL"), row.text("NEX$REMOTE_ADDRESS"),
                    row.text("NEX$REMOTE_HOST"), row.text("NEX$REMOTE_OS_USER"), row.text("NEX$REMOTE_PROCESS")));
        }
        list.sort(Comparator.comparingLong(LinkInfo::id).reversed());
        return list;
    }

    public static List<ConfigInfo> config(Connection connection) throws SQLException {
        List<ConfigInfo> list = new ArrayList<>();
        for (Row row : query(connection, "SELECT NEX$KEY, NEX$VALUE FROM NEX$CONFIG")) {
            list.add(new ConfigInfo(row.text("NEX$KEY"), row.text("NEX$VALUE")));
        }
        list.sort(Comparator.comparing(ConfigInfo::key, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    public static DatabaseInfo database(Connection connection) throws SQLException {
        List<Row> rows = query(connection, """
                SELECT NEX$DATABASE_NAME, NEX$DESCRIPTION, NEX$OWNER_NAME, NEX$CREATED,
                       NEX$FORMAT, NEX$SQL_DIALECT, NEX$PAGE_SIZE
                FROM NEX$DATABASE
                """);
        if (rows.isEmpty()) {
            throw new SQLException("NEX$DATABASE não devolveu linha");
        }
        Row row = rows.get(0);
        return new DatabaseInfo(row.text("NEX$DATABASE_NAME"), row.text("NEX$DESCRIPTION"), row.text("NEX$OWNER_NAME"),
                row.text("NEX$CREATED"), (int) row.number("NEX$FORMAT"), (int) row.number("NEX$SQL_DIALECT"),
                (int) row.number("NEX$PAGE_SIZE"));
    }

    public static List<MemberInfo> members(Connection connection) throws SQLException {
        List<MemberInfo> list = new ArrayList<>();
        for (Row row : query(connection, """
                SELECT NEX$ROLE_NAME, NEX$USER_NAME, NEX$ADMIN_OPTION, NEX$GRANTOR FROM NEX$ROLE_MEMBERS
                """)) {
            list.add(new MemberInfo(row.text("NEX$ROLE_NAME"), row.text("NEX$USER_NAME"),
                    row.bool("NEX$ADMIN_OPTION"), row.text("NEX$GRANTOR")));
        }
        return list;
    }

    private static int schemaOrder(String left, String right) {
        boolean leftPublic = left.equalsIgnoreCase("public");
        boolean rightPublic = right.equalsIgnoreCase("public");
        if (leftPublic != rightPublic) {
            return leftPublic ? -1 : 1;
        }
        return String.CASE_INSENSITIVE_ORDER.compare(left, right);
    }

    private static List<String> names(List<Row> rows, String column) {
        List<String> names = new ArrayList<>();
        for (Row row : rows) {
            String name = row.text(column);
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private static List<String> splitColumns(String packed) {
        if (packed == null || packed.isEmpty()) {
            return List.of();
        }
        String[] parts = packed.split("\u001f", -1);
        List<String> columns = new ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty()) {
                columns.add(part);
            }
        }
        return columns;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static List<Row> tryQuery(Connection connection, List<String> warnings, String label, String sql) {
        try {
            return query(connection, sql);
        } catch (SQLException exception) {
            warnings.add(label + ": " + exception.getMessage());
            return List.of();
        }
    }

    private static List<Row> query(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            ResultSetMetaData meta = result.getMetaData();
            int count = meta.getColumnCount();
            List<Row> rows = new ArrayList<>();
            while (result.next()) {
                Map<String, Object> values = new LinkedHashMap<>();
                for (int i = 1; i <= count; i++) {
                    values.put(meta.getColumnLabel(i), result.getObject(i));
                }
                rows.add(new Row(values));
            }
            return rows;
        }
    }

    private record Parameter(int direction, int number, Ddl.Field field) {
    }

    private record IndexColumn(int position, String name) {
    }

    private record Row(Map<String, Object> values) {
        String text(String column) {
            Object value = values.get(column);
            return value == null ? "" : String.valueOf(value);
        }

        boolean bool(String column) {
            Object value = values.get(column);
            if (value instanceof Boolean bool) {
                return bool;
            }
            if (value instanceof Number number) {
                return number.intValue() != 0;
            }
            if (value instanceof String text) {
                return text.equalsIgnoreCase("true") || text.equals("1");
            }
            return false;
        }

        long number(String column) {
            Object value = values.get(column);
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value instanceof String text && !text.isBlank()) {
                return Long.parseLong(text.trim());
            }
            return 0;
        }
    }

    static String fold(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
