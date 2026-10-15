package io.github.gabrielmmoraes1999.nexora;

import java.util.List;

/**
 * Structure of a stored table: columns in creation order and indexes.
 *
 * @param schema SQL schema. The default is {@code public}
 */
public record TableDescription(
        String schema,
        String name,
        List<TableColumn> columns,
        List<TableIndex> indexes,
        String primaryConstraint,
        String primaryIndex,
        boolean primaryDescending,
        List<ForeignKey> foreignKeys) {

    public TableDescription {
        columns = List.copyOf(columns);
        indexes = List.copyOf(indexes);
        primaryConstraint = primaryConstraint == null ? "" : primaryConstraint;
        primaryIndex = primaryIndex == null ? "" : primaryIndex;
        foreignKeys = foreignKeys == null ? List.of() : List.copyOf(foreignKeys);
    }

    /**
     * @param primaryKeyPosition position in the primary key, starting at 1; 0 if the column is not part of it
     * @param defaultSql SQL literal of the DEFAULT, or {@code null} when the column has no default
     */
    public record TableColumn(
            String table,
            String name,
            DataType type,
            int length,
            int scale,
            boolean nullable,
            boolean autoIncrement,
            int primaryKeyPosition,
            String defaultSql) {
    }

    public record TableIndex(String table, String name, boolean unique, List<String> columns, boolean descending) {
        public TableIndex {
            columns = List.copyOf(columns);
        }
    }

    public record ForeignKey(
            String name,
            List<String> columns,
            String referencedSchema,
            String referencedTable,
            List<String> referencedColumns,
            String indexName,
            boolean descending,
            ReferentialAction onUpdate,
            ReferentialAction onDelete) {
        public ForeignKey {
            columns = List.copyOf(columns);
            referencedColumns = List.copyOf(referencedColumns);
            onUpdate = onUpdate == null ? ReferentialAction.NO_ACTION : onUpdate;
            onDelete = onDelete == null ? ReferentialAction.NO_ACTION : onDelete;
        }
    }
}
