package io.github.gabrielmmoraes1999.seadb;

/**
 * Description of a column returned by a query.
 *
 * @param name display name, including the alias when there is one
 * @param type type inferred from the expression
 * @param schema table schema, or empty when the expression is not a column
 * @param table column table, or empty when the expression is not a column
 */
public record ColumnMeta(String name, DataType type, String schema, String table) {

    public ColumnMeta(String name, DataType type) {
        this(name, type, "", "");
    }
}
