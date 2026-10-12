package io.github.gabrielmmoraes1999.nexora;

/**
 * Descrição de uma coluna devolvida por uma consulta.
 *
 * @param name nome de exibição, já com alias quando houver
 * @param type tipo inferido da expressão
 * @param schema esquema da tabela, ou vazio quando a expressão não é uma coluna
 * @param table tabela da coluna, ou vazio quando a expressão não é uma coluna
 */
public record ColumnMeta(String name, DataType type, String schema, String table) {

    public ColumnMeta(String name, DataType type) {
        this(name, type, "", "");
    }
}
