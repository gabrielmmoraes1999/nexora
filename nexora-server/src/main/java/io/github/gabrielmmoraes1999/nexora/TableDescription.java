package io.github.gabrielmmoraes1999.nexora;

import java.util.List;

/**
 * Estrutura de uma tabela já gravada: colunas na ordem de criação e índices.
 *
 * @param schema esquema SQL. O padrão é {@code public}
 */
public record TableDescription(String schema, String name, List<TableColumn> columns, List<TableIndex> indexes) {

    public TableDescription {
        columns = List.copyOf(columns);
        indexes = List.copyOf(indexes);
    }

    /**
     * @param primaryKeyPosition posição na chave primária, começando em 1; 0 se a coluna não fizer parte dela
     * @param defaultSql literal SQL do DEFAULT, ou {@code null} quando a coluna não tem padrão
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

    public record TableIndex(String table, String name, boolean unique, List<String> columns) {
        public TableIndex {
            columns = List.copyOf(columns);
        }
    }
}
