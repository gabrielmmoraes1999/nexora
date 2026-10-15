package io.github.gabrielmmoraes1999.nexora.studio;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Texto SQL de criação, extração e remoção. O studio executa esse texto pelo JDBC.
 */
public final class Ddl {

    private Ddl() {
    }

    public record ColumnDraft(
            String name,
            String type,
            int size,
            int scale,
            boolean nullable,
            boolean primaryKey,
            boolean unique,
            boolean autoIncrement,
            String defaultSql
    ) {
    }

    public record ColumnInfo(
            String name,
            String typeName,
            int size,
            int scale,
            boolean nullable,
            String defaultSql,
            boolean autoIncrement,
            int primaryKeyPosition
    ) {
    }

    public record IndexInfo(String name, boolean unique, List<String> columns, boolean descending) {
        public IndexInfo {
            columns = List.copyOf(columns);
        }
    }

    public record ConstraintInfo(
            String name,
            List<String> columns,
            String index,
            boolean descending,
            String referencedSchema,
            String referencedTable,
            List<String> referencedColumns,
            String onUpdate,
            String onDelete) {
        public ConstraintInfo {
            columns = List.copyOf(columns);
            referencedColumns = referencedColumns == null ? List.of() : List.copyOf(referencedColumns);
        }
    }

    public record Field(String name, String typeName, int size, int scale, boolean input) {
    }

    public static String typeSql(String typeName, int size, int scale) {
        String type = typeName == null ? "" : typeName.toUpperCase(Locale.ROOT);
        return switch (type) {
            case "VARCHAR", "CHAR", "CHARACTER", "CHARACTER VARYING" -> type + "(" + Math.max(size, 1) + ")";
            case "DECIMAL", "NUMERIC" -> type + "(" + Math.max(size, 1) + ", " + Math.max(scale, 0) + ")";
            default -> type;
        };
    }

    public static String create(String schema, String table, List<ColumnDraft> columns) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("informe o nome da tabela");
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("a tabela precisa de ao menos uma coluna");
        }
        List<String> primary = new ArrayList<>();
        for (ColumnDraft column : columns) {
            if (column.primaryKey) {
                if (column.name == null || column.name.isBlank()) {
                    throw new IllegalArgumentException("informe o nome da coluna da chave");
                }
                primary.add(column.name.trim());
            }
        }
        boolean inlineKey = primary.size() == 1;
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE ").append(SqlNames.qualify(schema, table.trim())).append(" (\n");
        for (int i = 0; i < columns.size(); i++) {
            ColumnDraft column = columns.get(i);
            if (column.name == null || column.name.isBlank()) {
                throw new IllegalArgumentException("informe o nome da coluna " + (i + 1));
            }
            if (column.type == null || column.type.isBlank()) {
                throw new IllegalArgumentException("informe o tipo de " + column.name);
            }
            if (column.autoIncrement && !integerType(column.type)) {
                throw new IllegalArgumentException("AUTOINCREMENT só vale em SMALLINT, INTEGER ou BIGINT");
            }
            if (i > 0) {
                sql.append(",\n");
            }
            sql.append("    ");
            appendColumn(sql, column.name.trim(), column.type, column.size, column.scale,
                    column.nullable, column.defaultSql, inlineKey && column.primaryKey,
                    column.unique && !column.primaryKey, column.autoIncrement);
        }
        if (!inlineKey && !primary.isEmpty()) {
            sql.append(",\n    PRIMARY KEY (").append(quotedList(primary)).append(')');
        }
        sql.append("\n)");
        return sql.toString();
    }

    public static String table(String schema, String name, List<ColumnInfo> columns, List<IndexInfo> indexes) {
        return table(schema, name, columns, indexes, null, List.of());
    }

    public static String table(String schema, String name, List<ColumnInfo> columns, List<IndexInfo> indexes,
                               ConstraintInfo primaryKey, List<ConstraintInfo> foreignKeys) {
        List<String> primary = new ArrayList<>();
        for (ColumnInfo column : columns) {
            if (column.primaryKeyPosition > 0) {
                while (primary.size() < column.primaryKeyPosition) {
                    primary.add("");
                }
                primary.set(column.primaryKeyPosition - 1, column.name);
            }
        }
        primary.removeIf(String::isBlank);
        if (primaryKey != null && !primaryKey.columns.isEmpty()) {
            primary = new ArrayList<>(primaryKey.columns);
        }
        String constraint = primaryKey == null || primaryKey.name == null || primaryKey.name.isBlank()
                ? "PK_" + name : primaryKey.name;
        String indexName = primaryKey == null || primaryKey.index == null || primaryKey.index.isBlank()
                ? constraint : primaryKey.index;
        boolean descending = primaryKey != null && primaryKey.descending;
        boolean namedKey = primaryKey != null
                && (descending || !constraint.equalsIgnoreCase("PK_" + name) || !indexName.equalsIgnoreCase(constraint));
        boolean inlineKey = primary.size() == 1 && !namedKey;
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE ").append(SqlNames.qualify(schema, name)).append(" (\n");
        for (int i = 0; i < columns.size(); i++) {
            ColumnInfo column = columns.get(i);
            if (i > 0) {
                sql.append(",\n");
            }
            sql.append("    ");
            appendColumn(sql, column.name, column.typeName, column.size, column.scale,
                    column.nullable, column.defaultSql, inlineKey && column.primaryKeyPosition > 0,
                    false, column.autoIncrement);
        }
        if (!primary.isEmpty() && !inlineKey) {
            sql.append(",\n    ");
            if (namedKey) {
                sql.append("CONSTRAINT ").append(SqlNames.quote(constraint)).append(' ');
            }
            sql.append("PRIMARY KEY (").append(quotedList(primary)).append(')');
            if (namedKey && (descending || !indexName.equalsIgnoreCase(constraint))) {
                sql.append(" USING ");
                if (descending) {
                    sql.append("DESCENDING ");
                }
                sql.append("INDEX ").append(SqlNames.quote(indexName));
            }
        }
        for (IndexInfo index : indexes) {
            if (index.unique && reservedUnique(index.name)) {
                sql.append(",\n    UNIQUE (").append(quotedList(index.columns)).append(')');
            }
        }
        sql.append("\n)");
        if (foreignKeys != null) {
            for (ConstraintInfo foreign : foreignKeys) {
                sql.append(";\n\nALTER TABLE ").append(SqlNames.qualify(schema, name))
                        .append(" ADD CONSTRAINT ").append(SqlNames.quote(foreign.name))
                        .append(" FOREIGN KEY (").append(quotedList(foreign.columns))
                        .append(") REFERENCES ").append(SqlNames.qualify(foreign.referencedSchema, foreign.referencedTable))
                        .append(" (").append(quotedList(foreign.referencedColumns)).append(')');
                appendAction(sql, "DELETE", foreign.onDelete);
                appendAction(sql, "UPDATE", foreign.onUpdate);
                if (foreign.descending || (foreign.index != null && !foreign.index.equalsIgnoreCase(foreign.name))) {
                    sql.append(" USING ");
                    if (foreign.descending) {
                        sql.append("DESCENDING ");
                    }
                    sql.append("INDEX ").append(SqlNames.quote(foreign.index));
                }
            }
        }
        for (IndexInfo index : indexes) {
            if (index.name == null || reservedIndex(index.name)) {
                continue;
            }
            if (primaryKey != null && index.name.equalsIgnoreCase(indexName)) {
                continue;
            }
            if (primaryKey == null && index.unique && sameColumns(index.columns, primary)) {
                continue;
            }
            if (foreignKeys != null && foreignIndex(foreignKeys, index.name)) {
                continue;
            }
            sql.append(";\n\n").append(createIndex(index.unique, index.name, schema, name, index.columns));
        }
        return sql.toString();
    }

    private static void appendAction(StringBuilder sql, String event, String rule) {
        if (rule == null || rule.isBlank() || rule.equalsIgnoreCase("NO ACTION")) {
            return;
        }
        sql.append(" ON ").append(event).append(' ').append(rule);
    }

    private static boolean foreignIndex(List<ConstraintInfo> foreignKeys, String name) {
        for (ConstraintInfo foreign : foreignKeys) {
            if (foreign.index != null && foreign.index.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
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

    public static String createIndex(boolean unique, String index, String schema, String table, List<String> columns) {
        if (index == null || index.isBlank()) {
            throw new IllegalArgumentException("informe o nome do índice");
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("escolha ao menos uma coluna");
        }
        StringBuilder sql = new StringBuilder("CREATE ");
        if (unique) {
            sql.append("UNIQUE ");
        }
        sql.append("INDEX ").append(SqlNames.quote(index.trim())).append(" ON ")
                .append(SqlNames.qualify(schema, table)).append(" (").append(quotedList(columns)).append(')');
        return sql.toString();
    }

    public static String addColumn(String schema, String table, ColumnDraft column) {
        if (column.name == null || column.name.isBlank()) {
            throw new IllegalArgumentException("informe o nome da coluna");
        }
        if (column.type == null || column.type.isBlank()) {
            throw new IllegalArgumentException("informe o tipo da coluna");
        }
        StringBuilder sql = new StringBuilder();
        sql.append("ALTER TABLE ").append(SqlNames.qualify(schema, table)).append(" ADD COLUMN ");
        appendColumn(sql, column.name.trim(), column.type, column.size, column.scale,
                column.nullable, column.defaultSql, false, false, false);
        return sql.toString();
    }

    public static String dropColumn(String schema, String table, String column) {
        return "ALTER TABLE " + SqlNames.qualify(schema, table) + " DROP COLUMN " + SqlNames.quote(column);
    }

    public static String dropIndex(String schema, String table, String index) {
        return "DROP INDEX " + SqlNames.quote(index) + " ON " + SqlNames.qualify(schema, table);
    }

    public static String dropObject(Node.Kind kind, String schema, String name) {
        return switch (kind) {
            case TABLE -> "DROP TABLE " + SqlNames.qualify(schema, name);
            case VIEW -> "DROP VIEW " + SqlNames.qualify(schema, name);
            case PROCEDURE -> "DROP PROCEDURE " + SqlNames.quote(name);
            case TRIGGER -> "DROP TRIGGER " + SqlNames.quote(name);
            case GENERATOR -> "DROP GENERATOR " + SqlNames.quote(name);
            case ROLE -> "DROP ROLE " + SqlNames.quote(name);
            case SCHEMA -> "DROP SCHEMA " + SqlNames.quote(name) + " RESTRICT";
            default -> null;
        };
    }

    public static String grant(List<String> privileges, String schema, String table, String grantee, boolean grantOption) {
        if (privileges == null || privileges.isEmpty()) {
            throw new IllegalArgumentException("escolha ao menos um privilégio");
        }
        if (grantee == null || grantee.isBlank()) {
            throw new IllegalArgumentException("informe o usuário, o papel ou PUBLIC");
        }
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("informe a tabela");
        }
        StringBuilder sql = new StringBuilder("GRANT ");
        sql.append(String.join(", ", privileges));
        sql.append(" ON ").append(SqlNames.qualify(schema, table));
        sql.append(" TO ").append(SqlNames.principal(grantee.trim()));
        if (grantOption) {
            sql.append(" WITH GRANT OPTION");
        }
        return sql.toString();
    }

    public static String revoke(String privilege, String schema, String table, String grantee) {
        return "REVOKE " + privilege + " ON " + SqlNames.qualify(schema, table) + " FROM " + SqlNames.principal(grantee);
    }

    public static String grantRole(String role, String user, boolean adminOption) {
        if (role == null || role.isBlank() || user == null || user.isBlank()) {
            throw new IllegalArgumentException("informe o papel e o usuário");
        }
        StringBuilder sql = new StringBuilder("GRANT ").append(SqlNames.quote(role.trim()))
                .append(" TO ").append(SqlNames.principal(user.trim()));
        if (adminOption) {
            sql.append(" WITH ADMIN OPTION");
        }
        return sql.toString();
    }

    public static String procedure(String schema, String name, List<Field> parameters, String body) {
        List<Field> inputs = new ArrayList<>();
        List<Field> outputs = new ArrayList<>();
        for (Field field : parameters) {
            (field.input ? inputs : outputs).add(field);
        }
        StringBuilder sql = new StringBuilder("CREATE PROCEDURE ").append(SqlNames.qualify(schema, name));
        if (!inputs.isEmpty()) {
            sql.append(" (").append(parameters(inputs)).append(')');
        }
        if (!outputs.isEmpty()) {
            sql.append("\nRETURNS (").append(parameters(outputs)).append(')');
        }
        sql.append("\nAS\n").append(body == null ? "" : body.strip());
        return sql.toString();
    }

    public static String trigger(String name, String relation, boolean inactive, String type, int position, String body) {
        return "CREATE TRIGGER " + SqlNames.quote(name) + " FOR " + qualifyStored(relation) + "\n"
                + (inactive ? "INACTIVE " : "ACTIVE ")
                + (type == null ? "" : type)
                + " POSITION " + position + "\nAS\n"
                + (body == null ? "" : body.strip());
    }

    public static String view(String schema, String name, List<String> columns, String source) {
        StringBuilder sql = new StringBuilder("CREATE VIEW ").append(SqlNames.qualify(schema, name));
        if (columns != null && !columns.isEmpty()) {
            sql.append(" (").append(quotedList(columns)).append(')');
        }
        sql.append(" AS\n").append(source == null ? "" : source.strip());
        return sql.toString();
    }

    public static String qualifyStored(String catalogName) {
        if (catalogName == null || catalogName.isBlank()) {
            return "\"\"";
        }
        int dot = catalogName.indexOf('.');
        if (dot < 0) {
            return SqlNames.quote(catalogName);
        }
        return SqlNames.quote(catalogName.substring(0, dot)) + "." + SqlNames.quote(catalogName.substring(dot + 1));
    }

    private static void appendColumn(StringBuilder sql, String name, String type, int size, int scale,
                                     boolean nullable, String defaultSql, boolean primaryKey, boolean unique,
                                     boolean autoIncrement) {
        sql.append(SqlNames.quote(name)).append(' ').append(typeSql(type, size, scale));
        if (defaultSql != null && !defaultSql.isBlank()) {
            sql.append(" DEFAULT ").append(defaultSql.trim());
        }
        if (!nullable && !primaryKey) {
            sql.append(" NOT NULL");
        }
        if (primaryKey) {
            sql.append(" PRIMARY KEY");
        }
        if (unique) {
            sql.append(" UNIQUE");
        }
        if (autoIncrement) {
            sql.append(" AUTOINCREMENT");
        }
    }

    private static String parameters(List<Field> fields) {
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            Field field = fields.get(i);
            sql.append(SqlNames.quote(field.name)).append(' ').append(typeSql(field.typeName, field.size, field.scale));
        }
        return sql.toString();
    }

    private static String quotedList(List<String> names) {
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(SqlNames.quote(names.get(i)));
        }
        return sql.toString();
    }

    private static boolean integerType(String type) {
        String folded = type.toUpperCase(Locale.ROOT);
        return folded.equals("SMALLINT") || folded.equals("INTEGER") || folded.equals("BIGINT") || folded.equals("INT");
    }

    private static boolean reservedIndex(String name) {
        return name.equalsIgnoreCase("PRIMARY") || reservedUnique(name);
    }

    private static boolean reservedUnique(String name) {
        if (name == null) {
            return false;
        }
        String folded = name.toLowerCase(Locale.ROOT);
        if (!folded.startsWith("unique_")) {
            return false;
        }
        String suffix = folded.substring("unique_".length());
        if (suffix.isEmpty()) {
            return false;
        }
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
