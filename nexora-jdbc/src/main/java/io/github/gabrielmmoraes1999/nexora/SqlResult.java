package io.github.gabrielmmoraes1999.nexora;

import java.util.Iterator;
import java.util.List;

/**
 * Resultado do último comando de um script. Consultas trazem linhas;
 * os demais comandos trazem a quantidade de registros afetados.
 */
public final class SqlResult implements Iterable<Row> {

    private final boolean query;
    private final int affectedRows;
    private final List<ColumnMeta> columns;
    private final List<Row> rows;

    private SqlResult(boolean query, int affectedRows, List<ColumnMeta> columns, List<Row> rows) {
        this.query = query;
        this.affectedRows = affectedRows;
        this.columns = columns;
        this.rows = rows;
    }

    public static SqlResult query(List<ColumnMeta> columns, List<Row> rows) {
        return new SqlResult(true, rows.size(), List.copyOf(columns), List.copyOf(rows));
    }

    public static SqlResult update(int affectedRows) {
        return new SqlResult(false, affectedRows, List.of(), List.of());
    }

    public boolean isQuery() {
        return query;
    }

    public int affectedRows() {
        return affectedRows;
    }

    public List<ColumnMeta> columns() {
        return columns;
    }

    public List<Row> rows() {
        return rows;
    }

    public int size() {
        return rows.size();
    }

    @Override
    public Iterator<Row> iterator() {
        return rows.iterator();
    }

    @Override
    public String toString() {
        if (!query) {
            return "afetadas=" + affectedRows;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                builder.append(" | ");
            }
            builder.append(columns.get(i).name());
        }
        builder.append(System.lineSeparator());
        for (Row row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    builder.append(" | ");
                }
                builder.append(Values.display(row.get(i)));
            }
            builder.append(System.lineSeparator());
        }
        return builder.toString();
    }
}
