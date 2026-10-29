package io.github.gabrielmmoraes1999.seadb.studio;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionTest {

    @Test
    void completaPeloContextoDoComando() {
        String from = "SELECT * FROM cli";
        Completion.Item cliente = item(from, "cliente");
        assertNotNull(cliente);
        assertEquals("cliente", cliente.insert());
        assertEquals("table", cliente.detail());
        assertFalse(labels(from).contains("pedido"));

        assertTrue(labels("SELECT * FROM vendas.").contains("pedido"));
        assertFalse(labels("SELECT * FROM vendas.").contains("cliente"));

        Completion.Item other = item("SELECT * FROM ped", "vendas.pedido");
        assertNotNull(other);
        assertEquals("vendas.pedido", other.insert());

        Completion.Item quoted = item("SELECT * FROM ord", "order");
        assertNotNull(quoted);
        assertEquals("\"order\"", quoted.insert());

        String column = "SELECT n FROM cliente";
        assertTrue(Completion.suggest(column, "SELECT n".length(), catalog(columns())).items().stream()
                .map(Completion.Item::label).toList().contains("nome"));
        assertTrue(labels("SELECT id FROM cliente c WHERE c.").contains("nome"));
        assertTrue(labels("INSERT INTO cliente (").contains("id"));
        assertTrue(labels("SELECT c").contains("COUNT("));
        assertTrue(labels("CREATE ").contains("TABLE"));
        assertTrue(labels("CREATE TABLE t (id ").contains("INTEGER"));
        assertTrue(labels("CREATE TABLE t (id INTEGER ").contains("NOT"));
        assertTrue(labels("DROP INDEX i").contains("idx_nome"));
        assertTrue(labels("EXECUTE PROCEDURE e").contains("eco"));
        assertTrue(labels("SET SCHEMA v").contains("vendas"));
        assertTrue(labels("SET GENERATOR g").contains("gen_item"));

        Completion.Item schema = item("SELECT * FROM ven", "vendas");
        assertNotNull(schema);
        assertEquals("vendas.", schema.insert());
        assertTrue(schema.openAgain());

        assertTrue(labels("'SELECT FROM cli").isEmpty());
        assertTrue(labels("-- SELECT FROM cli").isEmpty());
        assertFalse(labels("SELECT * FROM cliente; SELECT n").contains("nome"));
    }

    @Test
    void pedeAsColunasQueAindaNaoCarregou() {
        Completion.Catalog bare = catalog(Map.of());
        String sql = "SELECT n FROM cliente";
        Completion.Result result = Completion.suggest(sql, "SELECT n".length(), bare);
        assertEquals(1, result.missing().size());
        assertEquals("cliente", result.missing().get(0).name());
        assertEquals("public", result.missing().get(0).schema());
    }

    @Test
    void completaNoMeioDoNome() {
        String sql = "SELECT * FROM cliente";
        int caret = sql.indexOf("cliente") + 3;
        List<String> labels = Completion.suggest(sql, caret, catalog(columns())).items().stream()
                .map(Completion.Item::label).toList();
        assertTrue(labels.contains("cliente"));
    }

    private static List<String> labels(String sql) {
        return Completion.suggest(sql, sql.length(), catalog(columns())).items().stream()
                .map(Completion.Item::label).toList();
    }

    private static Completion.Item item(String sql, String label) {
        for (Completion.Item item : Completion.suggest(sql, sql.length(), catalog(columns())).items()) {
            if (item.label().equals(label)) {
                return item;
            }
        }
        return null;
    }

    private static Map<String, List<String>> columns() {
        return Map.of(Completion.key("public", "cliente"), List.of("id", "nome"));
    }

    private static Completion.Catalog catalog(Map<String, List<String>> columns) {
        return new Completion.Catalog(
                "public",
                List.of(new Completion.ObjectName("public", "cliente"),
                        new Completion.ObjectName("vendas", "pedido"),
                        new Completion.ObjectName("public", "order")),
                List.of(new Completion.ObjectName("public", "nomes")),
                List.of(new Completion.ObjectName("public", "eco")),
                List.of(new Completion.ObjectName("public", "gen_item")),
                List.of(new Completion.ObjectName("public", "idx_nome")),
                List.of(),
                List.of("cliente_bi"),
                List.of("public", "vendas"),
                List.of("admin"),
                List.of("dba"),
                columns);
    }
}
