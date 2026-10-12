package io.github.gabrielmmoraes1999.nexora.studio;

/**
 * Identificadores no texto SQL. Aspas preservam o nome que o catálogo devolveu.
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
     * Nome como o catálogo grava privilégio e trigger: {@code tabela} em {@code public},
     * {@code esquema.tabela} nos demais.
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
     * Escapa {@code \%_} para {@code DatabaseMetaData} tratar o nome como literal.
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
                || folded.contains("NEX$");
    }
}
