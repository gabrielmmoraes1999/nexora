package io.github.gabrielmmoraes1999.seadb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryClauseTest {

    @TempDir
    Path dir;

    private Database open() {
        Path file = dir.resolve("banco.db");
        if (!Files.exists(file)) {
            Database.create(file, DatabaseOptions.defaults());
        }
        return Database.open(file, "dba", "dba");
    }

    @Test
    void agrupaEFiltraGrupos() {
        try (Database db = open()) {
            clientes(db);
            SqlResult cidades = db.query("""
                    SELECT cidade, COUNT(*) AS total
                    FROM cliente
                    GROUP BY cidade
                    ORDER BY cidade
                    """);
            assertEquals(Arrays.asList("Olinda", "Recife", null), textos(cidades, "cidade"));
            assertEquals(List.of(1L, 2L, 1L), numeros(cidades, "total"));

            SqlResult grandes = db.query("""
                    SELECT cidade, COUNT(*) AS total
                    FROM cliente
                    GROUP BY cidade
                    HAVING COUNT(*) > 1
                    """);
            assertEquals(1, grandes.size());
            assertEquals("Recife", grandes.rows().get(0).getString("cidade"));
            assertEquals(2L, grandes.rows().get(0).getLong("total"));

            SqlResult ordinal = db.query("""
                    SELECT cidade AS nome, COUNT(*) AS total
                    FROM cliente
                    GROUP BY 1
                    ORDER BY total DESC, nome
                    """);
            assertEquals("Recife", ordinal.rows().get(0).getString("nome"));

            SeaException solta = assertThrows(SeaException.class, () -> db.query("""
                    SELECT nome, COUNT(*) FROM cliente GROUP BY cidade
                    """));
            assertTrue(solta.getMessage().contains("GROUP BY"));

            SeaException estrela = assertThrows(SeaException.class, () -> db.query("""
                    SELECT * FROM cliente GROUP BY cidade
                    """));
            assertTrue(estrela.getMessage().contains("SELECT *"));

            db.execute("CREATE TABLE vazio (cidade VARCHAR(20))");
            assertEquals(0, db.query("SELECT cidade FROM vazio GROUP BY cidade").size());
            assertEquals(0L, db.query("SELECT COUNT(*) AS total FROM vazio").rows().get(0).getLong("total"));
            assertEquals(0, db.query("SELECT COUNT(*) AS total FROM vazio HAVING COUNT(*) > 0").size());
            assertEquals(1, db.query("SELECT COUNT(*) AS total FROM cliente HAVING COUNT(*) > 0").size());

            SeaException mistura = assertThrows(SeaException.class,
                    () -> db.query("SELECT nome, COUNT(*) FROM cliente"));
            assertTrue(mistura.getMessage().contains("GROUP BY"));
        }
    }

    @Test
    void combinaTabelas() {
        try (Database db = open()) {
            clientes(db);
            pedidos(db);
            db.execute("""
                    CREATE TABLE item (
                        id INTEGER PRIMARY KEY,
                        id_pedido INTEGER,
                        nome VARCHAR(40)
                    )
                    """);
            db.execute("""
                    INSERT INTO item (id, id_pedido, nome) VALUES
                    (100, 10, 'Lapis'),
                    (101, 10, 'Caderno'),
                    (102, 11, 'Borracha')
                    """);

            SqlResult interno = db.query("""
                    SELECT c.nome AS nome, p.total AS total
                    FROM cliente c
                    INNER JOIN pedido p ON c.id = p.id_cliente
                    ORDER BY c.nome, p.total
                    """);
            assertEquals(List.of("Ana", "Ana", "Bruno"), textos(interno, "nome"));
            assertEquals(List.of(50L, 100L, 80L), numeros(interno, "total"));

            SqlResult esquerdo = db.query("""
                    SELECT c.nome AS nome, p.id AS pedido
                    FROM cliente c
                    LEFT OUTER JOIN pedido p ON c.id = p.id_cliente
                    ORDER BY c.nome, p.id
                    """);
            assertEquals(List.of("Ana", "Ana", "Bruno", "Carla", "Duda"), textos(esquerdo, "nome"));
            assertNull(valor(esquerdo, "Carla", "pedido"));

            SqlResult direito = db.query("""
                    SELECT c.nome AS nome, p.id AS pedido
                    FROM cliente c
                    RIGHT JOIN pedido p ON c.id = p.id_cliente
                    ORDER BY p.id
                    """);
            assertEquals(List.of(10L, 11L, 12L, 13L), numeros(direito, "pedido"));
            assertNull(direito.rows().get(3).getString("nome"));

            SqlResult completo = db.query("""
                    SELECT c.nome AS nome, p.id AS pedido
                    FROM cliente c
                    FULL OUTER JOIN pedido p ON c.id = p.id_cliente
                    """);
            assertEquals(6, completo.size());

            assertEquals(16, db.query("""
                    SELECT c.id, p.id
                    FROM cliente c
                    CROSS JOIN pedido p
                    """).size());

            SqlResult dois = db.query("""
                    SELECT c.nome, i.nome AS item
                    FROM cliente c
                    JOIN pedido p ON c.id = p.id_cliente
                    JOIN item i ON p.id = i.id_pedido
                    ORDER BY i.nome
                    """);
            assertEquals(List.of("Borracha", "Caderno", "Lapis"), textos(dois, "item"));

            SeaException ambigua = assertThrows(SeaException.class, () -> db.query("""
                    SELECT id FROM cliente a JOIN cliente b ON a.id = b.id
                    """));
            assertTrue(ambigua.getMessage().contains("ambiguous column"));

            SeaException semOn = assertThrows(SeaException.class,
                    () -> db.query("SELECT * FROM cliente JOIN pedido"));
            assertTrue(semOn.getMessage().contains("ON"));

            SeaException cruzado = assertThrows(SeaException.class, () -> db.query("""
                    SELECT * FROM cliente CROSS JOIN pedido ON cliente.id = pedido.id_cliente
                    """));
            assertTrue(cruzado.getMessage().contains("CROSS JOIN"));

            SqlResult peloIndice = db.query("""
                    SELECT c.nome AS nome, p.total AS total
                    FROM pedido p
                    INNER JOIN cliente c ON c.id = p.id_cliente
                    WHERE p.id_cliente = 1
                    ORDER BY p.total
                    """);
            assertEquals(List.of("Ana", "Ana"), textos(peloIndice, "nome"));
            assertEquals(List.of(50L, 100L), numeros(peloIndice, "total"));

            SeaException procedure = assertThrows(SeaException.class, () -> db.query("""
                    SELECT n FROM serie(1) JOIN cliente ON cliente.id = n
                    """));
            assertTrue(procedure.getMessage().contains("procedure"));
        }
    }

    @Test
    void estrelaQualificada() {
        try (Database db = open()) {
            clientes(db);
            pedidos(db);
            SqlResult contas = db.query("""
                    SELECT cr.*
                      FROM cliente cr
                     INNER JOIN pedido v
                        ON v.id_cliente = cr.id
                     INNER JOIN cliente c
                        ON c.id = v.id_cliente
                     WHERE cr.id = 1
                     ORDER BY v.id
                    """);
            assertEquals(List.of("id", "nome", "cidade"),
                    contas.columns().stream().map(ColumnMeta::name).toList());
            assertEquals(List.of("Ana", "Ana"), textos(contas, "nome"));
            assertEquals(List.of(1L, 1L), numeros(contas, "id"));

            SqlResult misto = db.query("""
                    SELECT cr.*, v.total AS total
                      FROM cliente cr
                     INNER JOIN pedido v ON v.id_cliente = cr.id
                     WHERE cr.cidade = 'Recife'
                     ORDER BY v.total
                    """);
            assertEquals(List.of("id", "nome", "cidade", "total"),
                    misto.columns().stream().map(ColumnMeta::name).toList());
            assertEquals(List.of(50L, 80L, 100L), numeros(misto, "total"));

            SqlResult peloNome = db.query("SELECT cliente.* FROM cliente WHERE id = 2");
            assertEquals("Bruno", peloNome.rows().get(0).getString("nome"));
            assertEquals(3, peloNome.columns().size());

            db.execute("CREATE SCHEMA vendas");
            db.execute("CREATE TABLE vendas.cliente (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            db.execute("INSERT INTO vendas.cliente (id, nome) VALUES (7, 'Eva')");
            SqlResult esquema = db.query("SELECT vendas.cliente.* FROM vendas.cliente");
            assertEquals(List.of("id", "nome"), esquema.columns().stream().map(ColumnMeta::name).toList());
            assertEquals("Eva", esquema.rows().get(0).getString("nome"));
            SqlResult publico = db.query("SELECT public.cliente.* FROM cliente WHERE id = 3");
            assertEquals("Carla", publico.rows().get(0).getString("nome"));

            db.execute("CREATE VIEW recife AS SELECT c.* FROM cliente c WHERE c.cidade = 'Recife'");
            assertEquals(List.of("Ana", "Bruno"), textos(db.query("SELECT * FROM recife ORDER BY id"), "nome"));

            SqlResult apelido = db.query("""
                    WITH base AS (SELECT id, nome FROM cliente WHERE id = 4)
                    SELECT b.* FROM base b
                    """);
            assertEquals("Duda", apelido.rows().get(0).getString("nome"));

            db.execute("""
                    CREATE PROCEDURE serie (ate INTEGER)
                    RETURNS (n INTEGER)
                    AS
                    BEGIN
                      n = 1;
                      WHILE (n <= ate) DO
                      BEGIN
                        SUSPEND;
                        n = n + 1;
                      END
                    END
                    """);
            assertEquals(List.of(1L, 2L), numeros(db.query("SELECT s.* FROM serie(2) s ORDER BY n"), "n"));

            SeaException desconhecido = assertThrows(SeaException.class,
                    () -> db.query("SELECT x.* FROM cliente c"));
            assertTrue(desconhecido.getMessage().contains("unknown qualifier"));

            SeaException ambiguo = assertThrows(SeaException.class, () -> db.query("""
                    SELECT cliente.* FROM cliente a JOIN cliente b ON a.id = b.id
                    """));
            assertTrue(ambiguo.getMessage().contains("ambiguous qualifier"));

            SqlResult um = db.query("""
                    SELECT a.* FROM cliente a JOIN cliente b ON a.id = b.id WHERE a.id = 1
                    """);
            assertEquals(List.of("id", "nome", "cidade"), um.columns().stream().map(ColumnMeta::name).toList());
            assertEquals("Ana", um.rows().get(0).getString("nome"));

            SeaException apelidoEstrela = assertThrows(SeaException.class,
                    () -> db.query("SELECT c.* AS pessoa FROM cliente c"));
            assertTrue(apelidoEstrela.getMessage().contains("cannot have an alias"));
        }
    }

    @Test
    void uneConsultas() {
        try (Database db = open()) {
            clientes(db);
            db.execute("CREATE TABLE extra (nome VARCHAR(40))");
            db.execute("INSERT INTO extra (nome) VALUES ('Ana'), ('Eva')");
            SqlResult uniao = db.query("""
                    SELECT nome FROM cliente
                    UNION
                    SELECT nome FROM extra
                    ORDER BY nome
                    LIMIT 2 OFFSET 1
                    """);
            assertEquals(List.of("Bruno", "Carla"), textos(uniao, "nome"));

            SqlResult todas = db.query("""
                    SELECT nome FROM cliente WHERE nome = 'Ana'
                    UNION ALL
                    SELECT nome FROM extra
                    ORDER BY 1
                    """);
            assertEquals(List.of("Ana", "Ana", "Eva"), textos(todas, "nome"));

            SeaException colunas = assertThrows(SeaException.class, () -> db.query("""
                    SELECT nome FROM cliente UNION SELECT nome, cidade FROM cliente
                    """));
            assertTrue(colunas.getMessage().contains("column count"));

            SeaException tipos = assertThrows(SeaException.class, () -> db.query("""
                    SELECT id FROM cliente UNION SELECT nome FROM cliente
                    """));
            assertTrue(tipos.getMessage().contains("column types"));
        }
    }

    @Test
    void consultaAuxiliar() {
        try (Database db = open()) {
            clientes(db);
            SqlResult uma = db.query("""
                    WITH ativos (pessoa) AS (SELECT nome FROM cliente WHERE cidade = 'Recife')
                    SELECT pessoa FROM ativos ORDER BY pessoa
                    """);
            assertEquals(List.of("Ana", "Bruno"), textos(uma, "pessoa"));

            SqlResult varias = db.query("""
                    WITH base AS (SELECT cidade FROM cliente),
                         resumo AS (SELECT cidade, COUNT(*) AS total FROM base GROUP BY cidade)
                    SELECT total FROM resumo WHERE cidade = 'Recife'
                    """);
            assertEquals(2L, varias.rows().get(0).getLong("total"));

            SqlResult aninhada = db.query("""
                    WITH base AS (SELECT nome FROM cliente WHERE nome = 'Ana'),
                         meio AS (
                             WITH interna AS (SELECT nome FROM base)
                             SELECT nome FROM interna
                         )
                    SELECT nome FROM meio
                    """);
            assertEquals("Ana", aninhada.rows().get(0).getString("nome"));

            db.execute("CREATE TABLE item (id INTEGER, nome VARCHAR(20))");
            db.execute("INSERT INTO item (id, nome) VALUES (1, 'real')");
            SqlResult cobre = db.query("""
                    WITH item AS (SELECT 2 AS id, 'cte' AS nome)
                    SELECT nome FROM item
                    """);
            assertEquals("cte", cobre.rows().get(0).getString("nome"));

            SeaException propria = assertThrows(SeaException.class, () -> db.query("""
                    WITH item AS (SELECT nome FROM item) SELECT nome FROM item
                    """));
            assertTrue(propria.getMessage().contains("reference itself"));

            SeaException recursiva = assertThrows(SeaException.class, () -> db.query("""
                    WITH RECURSIVE item AS (SELECT nome FROM cliente) SELECT nome FROM item
                    """));
            assertTrue(recursiva.getMessage().contains("RECURSIVE"));
        }
    }

    @Test
    void viewEScript() {
        try (Database db = open()) {
            clientes(db);
            db.execute("""
                    CREATE VIEW por_cidade AS
                    SELECT cidade, COUNT(*) AS total
                    FROM cliente
                    GROUP BY cidade
                    """);
            SqlResult view = db.query("SELECT cidade, total FROM por_cidade WHERE cidade = 'Olinda'");
            assertEquals(1L, view.rows().get(0).getLong("total"));

            SeaException join = assertThrows(SeaException.class, () -> db.execute("""
                    CREATE VIEW juntos AS
                    SELECT c.nome
                    FROM cliente c
                    JOIN cliente d ON c.id = d.id
                    """));
            assertTrue(join.getMessage().contains("one table"));

            SqlResult script = db.execute("""
                    INSERT INTO cliente (id, nome, cidade) VALUES (5, 'Eva', 'Recife');
                    WITH nova AS (SELECT nome FROM cliente WHERE id = 5)
                    SELECT nome FROM nova
                    """);
            assertEquals("Eva", script.rows().get(0).getString("nome"));

            SeaException meio = assertThrows(SeaException.class, () -> db.execute("""
                    WITH nova AS (SELECT nome FROM cliente) SELECT nome FROM nova;
                    SELECT nome FROM cliente
                    """));
            assertTrue(meio.getMessage().contains("last statement"));
        }
    }

    @Test
    void agrupaProcedureECursor() {
        try (Database db = open()) {
            clientes(db);
            db.execute("""
                    CREATE PROCEDURE serie (ate INTEGER)
                    RETURNS (n INTEGER)
                    AS
                    BEGIN
                      n = 1;
                      WHILE (n <= ate) DO
                      BEGIN
                        SUSPEND;
                        n = n + 1;
                      END
                    END
                    """);
            SqlResult contagem = db.query("SELECT COUNT(*) AS total FROM serie(3)");
            assertEquals(3L, contagem.rows().get(0).getLong("total"));
            SqlResult grupos = db.query("""
                    SELECT n, COUNT(*) AS total
                    FROM serie(2)
                    GROUP BY n
                    ORDER BY n
                    """);
            assertEquals(List.of(1L, 2L), numeros(grupos, "n"));

            db.execute("""
                    CREATE PROCEDURE cidades
                    RETURNS (cidade VARCHAR(40), total BIGINT)
                    AS
                    BEGIN
                      FOR SELECT cidade, COUNT(*) FROM cliente GROUP BY cidade
                          INTO :cidade, :total
                      DO SUSPEND;
                    END
                    """);
            SqlResult viaCursor = db.query("SELECT total FROM cidades() WHERE cidade = 'Recife'");
            assertEquals(2L, viaCursor.rows().get(0).getLong("total"));
        }
    }

    @Test
    void joinPeloPrefixoDoIndice() {
        try (Database db = open()) {
            db.execute("""
                    CREATE TABLE cliente (
                        id_empresa INTEGER NOT NULL,
                        id_cliente INTEGER NOT NULL,
                        nome VARCHAR(40),
                        PRIMARY KEY (id_empresa, id_cliente)
                    )
                    """);
            db.execute("""
                    CREATE TABLE pedido (
                        id_empresa INTEGER NOT NULL,
                        id_pedido INTEGER NOT NULL,
                        id_cliente INTEGER,
                        PRIMARY KEY (id_empresa, id_pedido)
                    )
                    """);
            db.execute("""
                    INSERT INTO cliente (id_empresa, id_cliente, nome) VALUES
                    (3, 1, 'Ana'),
                    (3, 2, 'Bruno'),
                    (1, 1, 'Carla')
                    """);
            StringBuilder pedidos = new StringBuilder("INSERT INTO pedido (id_empresa, id_pedido, id_cliente) VALUES ");
            for (int i = 0; i < 1500; i++) {
                if (i > 0) {
                    pedidos.append(", ");
                }
                int empresa = i < 400 ? 3 : (i < 800 ? 1 : 9);
                Integer cliente = i == 10 ? null : (i % 2) + 1;
                pedidos.append("(").append(empresa).append(", ").append(i + 1).append(", ");
                pedidos.append(cliente == null ? "NULL" : cliente).append(")");
            }
            db.execute(pedidos.toString());

            SqlResult empresa = db.query("SELECT COUNT(*) AS n FROM pedido WHERE id_empresa = 3");
            assertEquals(400L, empresa.rows().get(0).getLong("n"));

            SqlResult junto = db.query("""
                    SELECT c.nome AS nome, COUNT(*) AS n
                    FROM pedido p
                    INNER JOIN cliente c
                      ON c.id_empresa = p.id_empresa
                     AND c.id_cliente = p.id_cliente
                    WHERE p.id_empresa = 3
                    GROUP BY c.nome
                    ORDER BY c.nome
                    """);
            assertEquals(List.of("Ana", "Bruno"), textos(junto, "nome"));
            assertEquals(List.of(199L, 200L), numeros(junto, "n"));

            SqlResult soEmpresa = db.query("""
                    SELECT COUNT(*) AS n
                    FROM pedido p
                    INNER JOIN cliente c ON c.id_empresa = p.id_empresa
                    WHERE p.id_empresa = 3
                    """);
            assertEquals(800L, soEmpresa.rows().get(0).getLong("n"));

            SqlResult esquerdo = db.query("""
                    SELECT p.id_pedido AS pedido, c.nome AS nome
                    FROM pedido p
                    LEFT JOIN cliente c
                      ON c.id_empresa = p.id_empresa
                     AND c.id_cliente = p.id_cliente
                    WHERE p.id_pedido = 11
                    """);
            assertEquals(11L, esquerdo.rows().get(0).getLong("pedido"));
            assertNull(esquerdo.rows().get(0).getString("nome"));

            SqlResult desigual = db.query("""
                    SELECT c.nome AS nome
                    FROM cliente c
                    INNER JOIN pedido p ON p.id_cliente > c.id_cliente AND p.id_empresa = c.id_empresa
                    WHERE c.id_empresa = 3 AND c.id_cliente = 1 AND p.id_empresa = 3
                    ORDER BY c.nome
                    """);
            assertEquals(200, desigual.size());
        }
    }

    private void clientes(Database db) {
        db.execute("""
                CREATE TABLE cliente (
                    id INTEGER PRIMARY KEY,
                    nome VARCHAR(40),
                    cidade VARCHAR(40)
                )
                """);
        db.execute("""
                INSERT INTO cliente (id, nome, cidade) VALUES
                (1, 'Ana', 'Recife'),
                (2, 'Bruno', 'Recife'),
                (3, 'Carla', 'Olinda'),
                (4, 'Duda', NULL)
                """);
    }

    private void pedidos(Database db) {
        db.execute("""
                CREATE TABLE pedido (
                    id INTEGER PRIMARY KEY,
                    id_cliente INTEGER,
                    total INTEGER
                )
                """);
        db.execute("""
                INSERT INTO pedido (id, id_cliente, total) VALUES
                (10, 1, 100),
                (11, 1, 50),
                (12, 2, 80),
                (13, 9, 5)
                """);
    }

    private Object valor(SqlResult result, String nome, String coluna) {
        for (Row row : result.rows()) {
            if (nome.equals(row.getString("nome"))) {
                return row.get(coluna);
            }
        }
        throw new AssertionError("missing " + nome);
    }

    private List<String> textos(SqlResult result, String coluna) {
        List<String> values = new ArrayList<>();
        for (Row row : result.rows()) {
            values.add(row.getString(coluna));
        }
        return values;
    }

    private List<Long> numeros(SqlResult result, String coluna) {
        List<Long> values = new ArrayList<>();
        for (Row row : result.rows()) {
            values.add(row.getLong(coluna));
        }
        return values;
    }
}
