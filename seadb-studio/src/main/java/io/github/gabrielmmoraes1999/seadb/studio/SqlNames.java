package io.github.gabrielmmoraes1999.seadb.studio;

/**
 * Identifiers in SQL text. Quotes preserve the name the catalog returned.
 */
public final class SqlNames {

    private SqlNames() {
    }

    public static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    public static String qualify(String schema, String name) {
        if (schema == null || schema.isBlank() || schema.equalsIgnoreCase("public")) {
            return quote(name);
        }
        return quote(schema) + "." + quote(name);
    }

    /**
     * Name as the catalog stores a privilege and a trigger: {@code table} in {@code public},
     * {@code schema.table} otherwise.
     */
    public static String catalog(String schema, String name) {
        if (schema == null || schema.isBlank() || schema.equalsIgnoreCase("public")) {
            return name;
        }
        return schema + "." + name;
    }

    public static String principal(String name) {
        if (name != null && name.equalsIgnoreCase("PUBLIC")) {
            return "PUBLIC";
        }
        return quote(name);
    }

    /**
     * Escapes {@code \%_} so {@code DatabaseMetaData} treats the name as a literal.
     */
    public static String likeExact(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    public static boolean same(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    public static boolean changesCatalog(String sql) {
        String folded = sql.toUpperCase(java.util.Locale.ROOT);
        return folded.contains("CREATE") || folded.contains("DROP") || folded.contains("ALTER")
                || folded.contains("GRANT") || folded.contains("REVOKE")
                || folded.contains("SET GENERATOR") || folded.contains("SET SCHEMA")
                || folded.contains("SEA$");
    }
}
