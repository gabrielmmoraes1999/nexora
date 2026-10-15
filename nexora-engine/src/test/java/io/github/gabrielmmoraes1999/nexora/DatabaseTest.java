package io.github.gabrielmmoraes1999.nexora;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {

    @TempDir
    Path dir;

    private Path arquivo() {
        return dir.resolve("banco.db");
    }

    private Database open() {
        Path file = arquivo();
        if (!Files.exists(file)) {
            Database.create(file, DatabaseOptions.defaults());
        }
        return Database.open(file, "dba", "dba");
    }

    @Test
    void exemploDeCadastro() {
        byte[] foto = new byte[]{1, 2, 3, 4, 5};
        try (Database db = open()) {
            db.execute("DROP TABLE IF EXISTS cliente");
            db.execute("""
                    CREATE TABLE cliente (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        nome VARCHAR(120) NOT NULL,
                        foto BLOB
                    )
                    """);
            db.execute("INSERT INTO cliente (nome, foto) VALUES (?, ?)", "Ana", foto);
            db.execute("BEGIN");
            db.execute("UPDATE cliente SET nome = ? WHERE id = 1", "Ana Silva");
            db.execute("COMMIT");
            Row linha = db.query("SELECT id, nome, LENGTH(foto) AS bytes FROM cliente").rows().get(0);
            assertEquals(1, linha.getInt("id"));
            assertEquals("Ana Silva", linha.getString("nome"));
            assertEquals(5L, linha.getLong("bytes"));
        }
    }

    @Test
    void criaInsereConsultaEReabre() throws Exception {
        byte[] foto = new byte[]{10, 20, 30, 40};
        try (Database db = open()) {
            db.execute("""
                    CREATE TABLE cliente (
                        id INTEGER PRIMARY KEY,
                        nome VARCHAR(40) NOT NULL,
                        foto BLOB
                    )
                    """);
            assertEquals(1, db.executeUpdate(
                    "INSERT INTO cliente (id, nome, foto) VALUES (?, ?, ?)", 1, "Ana", foto));
        }
        try (Database db = open()) {
            SqlResult result = db.query("SELECT id, nome, foto FROM cliente WHERE id = 1");
            assertEquals(1, result.size());
            Row row = result.rows().get(0);
            assertEquals(1L, row.getLong("id"));
            assertEquals("Ana", row.getString("nome"));
            assertArrayEquals(foto, row.getBytes("foto"));
            try (var arquivos = Files.list(dir)) {
                assertEquals(List.of("banco.db", "banco.log"), arquivos.map(path -> path.getFileName().toString()).sorted().toList());
            }
            assertTrue(Files.size(dir.resolve("banco.log")) > Format.LOG_HEADER_SIZE);
        }
    }

    @Test
    void inserirNaoFazOArquivoEncolher() throws Exception {
        Path file = arquivo();
        long anterior = 0;
        try (Database db = open()) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, nome VARCHAR(40))");
            anterior = Files.size(file);
            for (int i = 0; i < 400; i++) {
                db.execute("INSERT INTO t VALUES (?, ?)", i, "Ana " + i);
                long agora = Files.size(file);
                assertTrue(agora >= anterior, "file shrank on insert " + i + ": " + anterior + " -> " + agora);
                anterior = agora;
            }
        }
        try (Database db = open()) {
            assertEquals(400, db.query("SELECT id FROM t").size());
            assertEquals("Ana 0", db.query("SELECT nome FROM t WHERE id = 0").rows().get(0).getString("nome"));
        }
    }

    @Test
    void textoCurtoNaoReservaQuatroBytesPorCaractere() throws Exception {
        Path file = arquivo();
        try (Database db = open()) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, nome VARCHAR(120))");
            for (int i = 0; i < 200; i++) {
                db.execute("INSERT INTO t VALUES (?, ?)", i, "Ana");
            }
        }
        assertTrue(Files.size(file) < 500_000, "file is too large: " + Files.size(file));
        try (Database db = open()) {
            assertEquals("Ana", db.query("SELECT nome FROM t WHERE id = 0").rows().get(0).getString("nome"));
            assertEquals(200, db.query("SELECT id FROM t").size());
        }
    }

    @Test
    void blobNuloVazioEGrandeNoMesmoArquivo() {
        byte[] grande = new byte[20_000];
        Arrays.fill(grande, (byte) 7);
        try (Database db = open()) {
            db.execute("CREATE TABLE doc (id INTEGER PRIMARY KEY, dados BLOB)");
            db.execute("INSERT INTO doc VALUES (1, NULL), (2, X''), (3, ?)", grande);
            SqlResult result = db.query("SELECT id, dados FROM doc ORDER BY id");
            assertNull(result.rows().get(0).getBytes("dados"));
            assertArrayEquals(new byte[0], result.rows().get(1).getBytes("dados"));
            assertArrayEquals(grande, result.rows().get(2).getBytes("dados"));
        }
        try (Database db = open()) {
            assertArrayEquals(grande, db.query("SELECT dados FROM doc WHERE id = 3").rows().get(0).getBytes(0));
        }
    }

    @Test
    void reutilizaEspacoDeBlobENaoCopiaBlobEmUpdateDeOutraColuna() throws Exception {
        byte[] payload = new byte[50_000];
        Arrays.fill(payload, (byte) 9);
        Path file = arquivo();
        try (Database db = open()) {
            db.execute("CREATE TABLE b (id INTEGER PRIMARY KEY, nome VARCHAR(20), d BLOB)");
            db.execute("INSERT INTO b VALUES (1, 'a', ?)", payload);
            long depoisDoInsert = Files.size(file);
            db.execute("UPDATE b SET nome = 'b' WHERE id = 1");
            long depoisDoUpdate = Files.size(file);
            assertTrue(depoisDoUpdate - depoisDoInsert < payload.length / 2,
                    "update copiou o BLOB: " + depoisDoInsert + " -> " + depoisDoUpdate);
            db.execute("DELETE FROM b");
            db.execute("INSERT INTO b VALUES (2, 'c', ?)", payload);
            long depoisDoReuso = Files.size(file);
            assertTrue(depoisDoReuso - depoisDoInsert < payload.length / 2,
                    "BLOB was not reused: " + depoisDoInsert + " -> " + depoisDoReuso);
        }
    }

    @Test
    void deleteEncolheOArquivo() throws Exception {
        byte[] payload = new byte[200_000];
        Arrays.fill(payload, (byte) 3);
        Path file = arquivo();
        try (Database db = open()) {
            db.execute("CREATE TABLE produto (id INTEGER PRIMARY KEY, nome VARCHAR(40), foto BLOB)");
            db.execute("CREATE TABLE outro (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            for (int i = 0; i < 20; i++) {
                db.execute("INSERT INTO produto VALUES (?, ?, ?)", i, "item-" + i, payload);
            }
            db.execute("INSERT INTO outro VALUES (1, 'fica')");
        }
        long cheio = Files.size(file);
        try (Database db = open()) {
            db.execute("BEGIN");
            db.execute("DELETE FROM produto");
            db.execute("COMMIT");
            assertEquals(0, db.query("SELECT id FROM produto").size());
            assertEquals("fica", db.query("SELECT nome FROM outro").rows().get(0).getString("nome"));
        }
        long vazio = Files.size(file);
        assertTrue(vazio < cheio / 2, "file did not shrink: " + cheio + " -> " + vazio);
        try (Database db = open()) {
            assertEquals("fica", db.query("SELECT nome FROM outro").rows().get(0).getString("nome"));
            db.execute("INSERT INTO produto VALUES (1, 'novo', ?)", payload);
            assertArrayEquals(payload, db.query("SELECT foto FROM produto WHERE id = 1").rows().get(0).getBytes("foto"));
        }
    }

    @Test
    void transacaoConfirmaEDesfaz() {
        try (Database db = open()) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, nome VARCHAR(20), foto BLOB)");
            db.execute("INSERT INTO t VALUES (1, 'ana', X'0102')");
            db.execute("BEGIN");
            db.execute("UPDATE t SET nome = 'bia', foto = X'FFFF' WHERE id = 1");
            db.execute("INSERT INTO t VALUES (2, 'caio', NULL)");
            assertEquals("bia", db.query("SELECT nome FROM t WHERE id = 1").rows().get(0).getString(0));
            db.execute("ROLLBACK");
            assertFalse(db.transactionActive());
            SqlResult atual = db.query("SELECT nome, foto FROM t ORDER BY id");
            assertEquals(1, atual.size());
            assertEquals("ana", atual.rows().get(0).getString("nome"));
            assertArrayEquals(new byte[]{1, 2}, atual.rows().get(0).getBytes("foto"));
            db.execute("BEGIN");
            db.execute("UPDATE t SET nome = 'bia' WHERE id = 1");
            db.execute("COMMIT");
        }
        try (Database db = open()) {
            assertEquals("bia", db.query("SELECT nome FROM t").rows().get(0).getString(0));
        }
    }

    @Test
    void transacoesDeSessoesDiferentes() {
        try (Database db = open()) {
            Session uma = db.session();
            Session outra = db.login("dba", "dba");
            db.execute(uma, "CREATE TABLE t (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            db.execute(uma, "INSERT INTO t VALUES (1, 'ana')");

            db.execute(uma, "BEGIN");
            db.execute(uma, "INSERT INTO t VALUES (2, 'dia')");
            assertEquals("dia", db.execute(uma, "SELECT nome FROM t WHERE id = 2").rows().get(0).getString("nome"));
            assertEquals(0, db.execute(outra, "SELECT nome FROM t WHERE id = 2").size());
            db.execute(outra, "INSERT INTO t VALUES (3, 'eli')");
            db.execute(uma, "COMMIT");
            assertEquals(3, db.execute(outra, "SELECT id FROM t").size());

            db.execute(uma, "BEGIN");
            db.execute(uma, "UPDATE t SET nome = 'bia' WHERE id = 1");
            db.execute(outra, "UPDATE t SET nome = 'caio' WHERE id = 1");
            NexException conflito = assertThrows(NexException.class, () -> db.execute(uma, "COMMIT"));
            assertTrue(conflito.getMessage().contains("another transaction"));
            assertEquals("caio", db.execute(outra, "SELECT nome FROM t WHERE id = 1").rows().get(0).getString("nome"));
            db.execute(uma, "ROLLBACK");

            db.execute(uma, "BEGIN");
            db.execute(uma, "UPDATE t SET nome = 'lia' WHERE id = 3");
            NexException estrutura = assertThrows(NexException.class,
                    () -> db.execute(outra, "CREATE INDEX ix_nome ON t (nome)"));
            assertTrue(estrutura.getMessage().contains("using the table"));
            db.execute(uma, "COMMIT");
            assertEquals("lia", db.execute(outra, "SELECT nome FROM t WHERE id = 3").rows().get(0).getString("nome"));
            db.logout(outra);
        }
    }

    @Test
    void erroDeIntegridadeNaoDesfazATransacaoInteira() {
        try (Database db = open()) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
            db.execute("BEGIN");
            db.execute("INSERT INTO t VALUES (1)");
            assertThrows(NexException.class, () -> db.execute("INSERT INTO t VALUES (1), (2)"));
            db.execute("INSERT INTO t VALUES (2)");
            db.execute("COMMIT");
            assertEquals(2, db.query("SELECT id FROM t").size());
        }
    }

    @Test
    void ddlForaDaTransacaoExplicita() {
        try (Database db = open()) {
            db.execute("BEGIN");
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
            assertTrue(db.transactionActive());
            db.execute("ROLLBACK");
            assertTrue(db.tables().stream().anyMatch(name -> name.equalsIgnoreCase("t")));
            db.execute("DROP TABLE t");
            db.execute("CREATE TABLE IF NOT EXISTS t (id INTEGER PRIMARY KEY)");
            db.execute("CREATE TABLE IF NOT EXISTS t (id INTEGER)");
            db.execute("DROP TABLE IF EXISTS ausente");
            db.execute("DROP TABLE t");
            assertTrue(db.tables().isEmpty());
        }
    }

    @Test
    void ddlDeOutraTabelaDuranteATransacao() {
        try (Database db = open()) {
            db.execute("CREATE TABLE pedido (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            db.execute("INSERT INTO pedido VALUES (1, 'ana')");
            db.execute("BEGIN");
            db.execute("INSERT INTO pedido VALUES (2, 'bia')");
            db.execute("CREATE TABLE nota (id INTEGER PRIMARY KEY, texto VARCHAR(20))");
            NexException conflito = assertThrows(NexException.class,
                    () -> db.execute("CREATE INDEX ix_pedido ON pedido (nome)"));
            assertTrue(conflito.getMessage().contains("pedido"));
            assertTrue(conflito.getMessage().contains("COMMIT or ROLLBACK"));
            assertTrue(db.transactionActive());
            db.execute("CREATE UNIQUE INDEX ix_nota ON nota (id)");
            db.execute("ROLLBACK");
            assertFalse(db.transactionActive());
            assertEquals(1, db.query("SELECT id FROM pedido").size());
            assertEquals("ana", db.query("SELECT nome FROM pedido").rows().get(0).getString("nome"));
            assertEquals(0, db.query("SELECT id FROM nota").size());
            db.execute("INSERT INTO nota VALUES (1, 'ok')");
            assertThrows(NexException.class, () -> db.execute("INSERT INTO nota VALUES (1, 'dup')"));

            db.execute("BEGIN");
            db.execute("INSERT INTO pedido VALUES (2, 'bia')");
            db.execute("CREATE TABLE item (id INTEGER PRIMARY KEY)");
            db.execute("COMMIT");
            assertEquals(2, db.query("SELECT id FROM pedido").size());
            assertEquals(0, db.query("SELECT id FROM item").size());
        }
        try (Database db = open()) {
            assertEquals(2, db.query("SELECT id FROM pedido").size());
            assertEquals(1, db.query("SELECT id FROM nota").size());
            assertEquals(0, db.query("SELECT id FROM item").size());
            assertThrows(NexException.class, () -> db.execute("INSERT INTO nota VALUES (1, 'dup')"));
        }
    }

    @Test
    void tiposFiltrosFuncoesEPaginacao() {
        try (Database db = open()) {
            db.execute("""
                    CREATE TABLE item (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        nome VARCHAR(30),
                        ativo BOOLEAN,
                        preco DECIMAL(10, 2),
                        dia DATE,
                        quando TIMESTAMP,
                        nota VARCHAR(80)
                    )
                    """);
            db.execute("""
                    INSERT INTO item (nome, ativo, preco, dia, quando, nota) VALUES
                    ('Ana', TRUE, 10.5, '2024-01-02', '2024-01-02 03:04:05', 'olá'),
                    ('Bruno', FALSE, 3, '2024-05-01', '2024-05-01T00:00:00', NULL),
                    (NULL, NULL, NULL, NULL, NULL, NULL)
                    """);
            assertEquals(1L, db.query("SELECT id FROM item WHERE nome = 'Ana'").rows().get(0).getLong(0));
            assertEquals(1, db.query("SELECT id FROM item WHERE nome IS NULL").size());
            assertEquals(0, db.query("SELECT id FROM item WHERE nome = NULL").size());
            assertEquals(1, db.query("SELECT id FROM item WHERE nome LIKE 'A%' AND ativo = TRUE").size());
            assertEquals(2, db.query("SELECT id FROM item WHERE nome LIKE 'A%' OR nome LIKE 'B%'").size());
            Row ana = db.query("SELECT preco, dia, quando, LOWER(nome) AS nome FROM item WHERE id = 1").rows().get(0);
            assertEquals(0, ana.getDecimal("preco").compareTo(new java.math.BigDecimal("10.50")));
            assertEquals(LocalDate.of(2024, 1, 2), ana.getDate("dia"));
            assertEquals(LocalDateTime.of(2024, 1, 2, 3, 4, 5), ana.getTimestamp("quando"));
            assertEquals("ana", ana.getString("nome"));
            assertEquals(3L, db.query("SELECT COUNT(*) AS total FROM item").rows().get(0).getLong("total"));
            assertEquals("Bruno", db.query("SELECT nome FROM item WHERE nome IS NOT NULL ORDER BY nome DESC LIMIT 1 OFFSET 0")
                    .rows().get(0).getString(0));
            assertEquals(2, db.query("SELECT DISTINCT ativo FROM item WHERE ativo IS NOT NULL").size());
            assertEquals("x", db.query("SELECT COALESCE(nome, 'x') AS nome FROM item WHERE id = 3").rows().get(0).getString(0));
            assertEquals(3L, db.query("SELECT id FROM item").rows().get(2).getLong(0));
        }
    }

    @Test
    void chaveCompostaAlteracaoEParametros() {
        try (Database db = open()) {
            db.execute("""
                    CREATE TABLE par (
                        a INTEGER NOT NULL,
                        b INTEGER NOT NULL,
                        nome VARCHAR(10) UNIQUE,
                        PRIMARY KEY (a, b)
                    )
                    """);
            db.execute("INSERT INTO par VALUES (1, 1, 'a'); INSERT INTO par VALUES (?, ?, ?)", 1, 2, "b");
            assertThrows(NexException.class, () -> db.execute("INSERT INTO par VALUES (1, 2, 'c')"));
            db.execute("ALTER TABLE par ADD COLUMN extra VARCHAR(10) DEFAULT 'z'");
            assertEquals("z", db.query("SELECT extra FROM par WHERE a = 1 AND b = 1").rows().get(0).getString(0));
            db.execute("ALTER TABLE par DROP COLUMN extra");
            assertEquals(3, db.query("SELECT * FROM par").columns().size());
            assertEquals(2, db.query("SELECT a FROM par").size());
        }
    }

    @Test
    void chavePrimariaDepoisDaCriacao() {
        try (Database db = open()) {
            db.execute("""
                    CREATE TABLE pedido (
                        id INTEGER,
                        cliente_id INTEGER,
                        nota VARCHAR(20)
                    )
                    """);
            db.execute("INSERT INTO pedido VALUES (1, 10, 'a'), (2, 10, 'b')");
            db.execute("ALTER TABLE pedido ADD PRIMARY KEY (id)");
            TableDescription pedido = db.describeTables().stream()
                    .filter(table -> table.name().equals("pedido")).findFirst().orElseThrow();
            assertEquals(1, pedido.columns().get(0).primaryKeyPosition());
            assertFalse(pedido.columns().get(0).nullable());
            assertTrue(pedido.columns().get(1).nullable());
            assertEquals(List.of("id"), pedido.indexes().get(0).columns());
            assertEquals("PK_pedido", pedido.indexes().get(0).name());
            assertEquals("PK_pedido", pedido.primaryConstraint());
            assertThrows(NexException.class, () -> db.execute("INSERT INTO pedido VALUES (1, 11, 'c')"));
            assertThrows(NexException.class, () -> db.execute("INSERT INTO pedido (id, cliente_id) VALUES (NULL, 11)"));
            assertThrows(NexException.class, () -> db.execute("UPDATE pedido SET id = NULL WHERE id = 1"));
            assertThrows(NexException.class, () -> db.execute("ALTER TABLE pedido ADD PRIMARY KEY (cliente_id)"));
            assertThrows(NexException.class, () -> db.execute("ALTER TABLE pedido ADD PRIMARY KEY (ausente)"));

            db.execute("""
                    CREATE TABLE item (
                        pedido_id INTEGER,
                        numero INTEGER,
                        produto VARCHAR(20)
                    )
                    """);
            db.execute("INSERT INTO item VALUES (1, 1, 'x'), (1, 2, 'y')");
            db.execute("ALTER TABLE item ADD CONSTRAINT pk_item PRIMARY KEY (pedido_id, numero)");
            assertThrows(NexException.class, () -> db.execute("INSERT INTO item VALUES (1, 1, 'z')"));
            TableDescription item = db.describeTables().stream()
                    .filter(table -> table.name().equals("item")).findFirst().orElseThrow();
            assertEquals(List.of("pk_item"), item.indexes().stream().map(TableDescription.TableIndex::name).toList());
            assertEquals(2, item.columns().get(1).primaryKeyPosition());

            db.execute("CREATE TABLE dup (id INTEGER, nome VARCHAR(10))");
            db.execute("INSERT INTO dup VALUES (1, 'a'), (1, 'b')");
            NexException duplicada = assertThrows(NexException.class, () -> db.execute("ALTER TABLE dup ADD PRIMARY KEY (id)"));
            assertTrue(duplicada.getMessage().contains("primary key"));
            assertEquals(2, db.query("SELECT * FROM dup").size());
            assertEquals(0, db.describeTables().stream().filter(table -> table.name().equals("dup"))
                    .findFirst().orElseThrow().columns().get(0).primaryKeyPosition());

            db.execute("CREATE TABLE nulo (id INTEGER)");
            db.execute("INSERT INTO nulo VALUES (NULL)");
            NexException nula = assertThrows(NexException.class, () -> db.execute("ALTER TABLE nulo ADD PRIMARY KEY (id)"));
            assertTrue(nula.getMessage().contains("NULL"));

            db.execute("CREATE TABLE vazio (id INTEGER, foto BLOB)");
            assertThrows(NexException.class, () -> db.execute("ALTER TABLE vazio ADD PRIMARY KEY (foto)"));
            db.execute("ALTER TABLE vazio ADD PRIMARY KEY (id)");

            db.execute("CREATE TABLE aberto (id INTEGER)");
            db.execute("BEGIN");
            db.execute("INSERT INTO aberto VALUES (1)");
            NexException transacao = assertThrows(NexException.class,
                    () -> db.execute("ALTER TABLE aberto ADD PRIMARY KEY (id)"));
            assertTrue(transacao.getMessage().contains("transaction"));
            db.execute("ROLLBACK");
            db.execute("ALTER TABLE aberto ADD PRIMARY KEY (id)");
        }
        try (Database db = open()) {
            assertThrows(NexException.class, () -> db.execute("INSERT INTO pedido VALUES (2, 11, 'c')"));
            assertEquals(1, db.query("SELECT id FROM pedido WHERE id = 1").rows().get(0).getInt("id"));
            TableDescription item = db.describeTables().stream()
                    .filter(table -> table.name().equals("item")).findFirst().orElseThrow();
            assertEquals(1, item.columns().get(0).primaryKeyPosition());
            assertEquals(2, item.columns().get(1).primaryKeyPosition());
            assertFalse(item.columns().get(0).nullable());
            assertEquals(List.of("pk_item"), item.indexes().stream().map(TableDescription.TableIndex::name).toList());
        }
    }

    @Test
    void chavePrimariaEEstrangeiraCriamIndice() {
        try (Database db = open()) {
            db.execute("CREATE TABLE pedido (id INTEGER PRIMARY KEY)");
            db.execute("""
                    CREATE TABLE item (
                        id INTEGER,
                        pedido_id INTEGER,
                        extra INTEGER,
                        CONSTRAINT FK_item FOREIGN KEY (pedido_id) REFERENCES pedido (id)
                    )
                    """);
            db.execute("ALTER TABLE item ADD CONSTRAINT PK_item PRIMARY KEY (id) USING DESCENDING INDEX ix_item");
            TableDescription item = table(db, "item");
            assertEquals("PK_item", item.primaryConstraint());
            assertEquals("ix_item", item.primaryIndex());
            assertTrue(item.primaryDescending());
            assertEquals("FK_item", item.foreignKeys().get(0).name());
            assertEquals("FK_item", item.foreignKeys().get(0).indexName());
            assertFalse(item.foreignKeys().get(0).descending());
            assertEquals(List.of("pedido_id"), item.foreignKeys().get(0).columns());
            assertEquals(List.of("id"), item.foreignKeys().get(0).referencedColumns());
            assertEquals(ReferentialAction.NO_ACTION, item.foreignKeys().get(0).onUpdate());
            assertEquals(ReferentialAction.NO_ACTION, item.foreignKeys().get(0).onDelete());
            assertTrue(item.indexes().stream().anyMatch(index -> index.name().equals("FK_item") && !index.descending()));
            assertTrue(item.indexes().stream().anyMatch(index -> index.name().equals("ix_item") && index.descending() && index.unique()));

            db.execute("INSERT INTO pedido VALUES (1)");
            db.execute("INSERT INTO item VALUES (1, 1, NULL)");
            assertThrows(NexException.class, () -> db.execute("INSERT INTO item VALUES (2, 9, NULL)"));
            assertThrows(NexException.class, () -> db.execute("DELETE FROM pedido WHERE id = 1"));
            db.execute("INSERT INTO item (id) VALUES (2)");
            db.execute("ALTER TABLE item ADD FOREIGN KEY (extra) REFERENCES pedido (id)");
            TableDescription segunda = table(db, "item");
            assertEquals("FK_item_extra", segunda.foreignKeys().get(1).name());
            assertEquals("FK_item_extra", segunda.foreignKeys().get(1).indexName());
            assertFalse(segunda.foreignKeys().get(1).descending());
            assertThrows(NexException.class, () -> db.execute("DROP INDEX FK_item ON item"));
            assertThrows(NexException.class, () -> db.execute("DROP INDEX ix_item ON item"));
            assertThrows(NexException.class, () -> db.execute("DROP TABLE pedido"));
            db.execute("DELETE FROM item WHERE id = 2");
            assertThrows(NexException.class, () -> db.execute("DELETE FROM pedido WHERE id = 1"));
            db.execute("DELETE FROM item WHERE id = 1");
            db.execute("DELETE FROM pedido");
        }
        try (Database db = open()) {
            TableDescription item = table(db, "item");
            assertEquals("ix_item", item.primaryIndex());
            assertTrue(item.primaryDescending());
            assertEquals(List.of("FK_item", "FK_item_extra"),
                    item.foreignKeys().stream().map(TableDescription.ForeignKey::name).toList());
            assertEquals(0, db.query("SELECT id FROM item WHERE id = 1").size());
        }
    }

    @Test
    void regrasDeUpdateEDeleteNaChaveEstrangeira() {
        try (Database db = open()) {
            db.execute("CREATE TABLE pedido (id INTEGER PRIMARY KEY)");
            db.execute("""
                    CREATE TABLE item (
                        id INTEGER PRIMARY KEY,
                        pedido_id INTEGER,
                        extra INTEGER DEFAULT 2,
                        CONSTRAINT FK_item FOREIGN KEY (pedido_id) REFERENCES pedido (id)
                            ON UPDATE CASCADE ON DELETE SET NULL
                    )
                    """);
            TableDescription item = table(db, "item");
            assertEquals(ReferentialAction.CASCADE, item.foreignKeys().get(0).onUpdate());
            assertEquals(ReferentialAction.SET_NULL, item.foreignKeys().get(0).onDelete());
            db.execute("INSERT INTO pedido VALUES (1)");
            db.execute("INSERT INTO pedido VALUES (2)");
            db.execute("INSERT INTO item VALUES (1, 1, 2)");
            db.execute("UPDATE pedido SET id = 5 WHERE id = 1");
            assertEquals(5, db.query("SELECT pedido_id FROM item WHERE id = 1").rows().get(0).getInt("pedido_id"));
            db.execute("DELETE FROM pedido WHERE id = 5");
            assertNull(db.query("SELECT pedido_id FROM item WHERE id = 1").rows().get(0).get("pedido_id"));

            db.execute("""
                    ALTER TABLE item ADD CONSTRAINT FK_extra FOREIGN KEY (extra) REFERENCES pedido (id)
                    ON DELETE SET DEFAULT
                    """);
            db.execute("INSERT INTO pedido VALUES (3)");
            db.execute("INSERT INTO item VALUES (2, NULL, 3)");
            db.execute("DELETE FROM pedido WHERE id = 3");
            assertEquals(2, db.query("SELECT extra FROM item WHERE id = 2").rows().get(0).getInt("extra"));

            NexException semNulo = assertThrows(NexException.class, () -> db.execute("""
                    ALTER TABLE item ADD CONSTRAINT FK_id FOREIGN KEY (id) REFERENCES pedido (id) ON DELETE SET NULL
                    """));
            assertTrue(semNulo.getMessage().contains("SET NULL"));
            NexException semPadrao = assertThrows(NexException.class, () -> db.execute("""
                    ALTER TABLE item ADD CONSTRAINT FK_pedido FOREIGN KEY (pedido_id) REFERENCES pedido (id)
                    ON UPDATE SET DEFAULT
                    """));
            assertTrue(semPadrao.getMessage().contains("SET DEFAULT"));
        }
        try (Database db = open()) {
            TableDescription item = table(db, "item");
            assertEquals(ReferentialAction.CASCADE, item.foreignKeys().get(0).onUpdate());
            assertEquals(ReferentialAction.SET_NULL, item.foreignKeys().get(0).onDelete());
            assertEquals(ReferentialAction.NO_ACTION, item.foreignKeys().get(1).onUpdate());
            assertEquals(ReferentialAction.SET_DEFAULT, item.foreignKeys().get(1).onDelete());
        }
        try (Database db = open()) {
            db.execute("CREATE TABLE detalhe (id INTEGER PRIMARY KEY, item_id INTEGER)");
            db.execute("""
                    ALTER TABLE detalhe ADD FOREIGN KEY (item_id) REFERENCES item (id) ON DELETE CASCADE
                    """);
            db.execute("INSERT INTO item (id, pedido_id) VALUES (3, NULL)");
            db.execute("INSERT INTO detalhe VALUES (1, 3)");
            db.execute("DELETE FROM item WHERE id = 3");
            assertEquals(0, db.query("SELECT id FROM detalhe").size());

            db.execute("CREATE TABLE no (id INTEGER PRIMARY KEY, pai INTEGER)");
            db.execute("""
                    ALTER TABLE no ADD FOREIGN KEY (pai) REFERENCES no (id) ON DELETE CASCADE ON UPDATE CASCADE
                    """);
            db.execute("INSERT INTO no VALUES (1, NULL)");
            db.execute("INSERT INTO no VALUES (2, 1)");
            db.execute("INSERT INTO no VALUES (3, 2)");
            db.execute("UPDATE no SET id = 8 WHERE id = 1");
            assertEquals(8, db.query("SELECT pai FROM no WHERE id = 2").rows().get(0).getInt("pai"));
            db.execute("DELETE FROM no WHERE id = 8");
            assertEquals(0, db.query("SELECT id FROM no").size());

            db.execute("INSERT INTO pedido VALUES (4)");
            db.execute("INSERT INTO item VALUES (4, 4, 2)");
            db.execute("BEGIN");
            db.execute("DELETE FROM pedido WHERE id = 4");
            assertNull(db.query("SELECT pedido_id FROM item WHERE id = 4").rows().get(0).get("pedido_id"));
            db.execute("ROLLBACK");
            assertEquals(1, db.query("SELECT id FROM pedido WHERE id = 4").size());
            assertEquals(4, db.query("SELECT pedido_id FROM item WHERE id = 4").rows().get(0).getInt("pedido_id"));

            db.execute("CREATE TABLE a (id INTEGER PRIMARY KEY, b_id INTEGER)");
            db.execute("CREATE TABLE b (id INTEGER PRIMARY KEY, a_id INTEGER)");
            db.execute("ALTER TABLE a ADD FOREIGN KEY (b_id) REFERENCES b (id) ON DELETE CASCADE");
            db.execute("ALTER TABLE b ADD FOREIGN KEY (a_id) REFERENCES a (id) ON DELETE CASCADE");
            db.execute("INSERT INTO a VALUES (1, NULL)");
            db.execute("INSERT INTO b VALUES (1, 1)");
            db.execute("UPDATE a SET b_id = 1 WHERE id = 1");
            db.execute("DELETE FROM a WHERE id = 1");
            assertEquals(0, db.query("SELECT id FROM a").size());
            assertEquals(0, db.query("SELECT id FROM b").size());
        }
    }

    @Test
    void removeChavePrimariaEEstrangeiraJuntoComOIndice() {
        try (Database db = open()) {
            db.execute("CREATE TABLE pedido (id INTEGER, nota VARCHAR(20), codigo INTEGER UNIQUE)");
            db.execute("INSERT INTO pedido VALUES (1, 'a', 10)");
            db.execute("ALTER TABLE pedido ADD CONSTRAINT PK_pedido PRIMARY KEY (id) USING DESCENDING INDEX ix_pedido");
            db.execute("CREATE INDEX idx_nota ON pedido (nota)");
            db.execute("""
                    CREATE TABLE item (
                        id INTEGER PRIMARY KEY,
                        pedido_id INTEGER,
                        extra INTEGER,
                        CONSTRAINT FK_item FOREIGN KEY (pedido_id) REFERENCES pedido (id)
                    )
                    """);
            db.execute("""
                    ALTER TABLE item ADD CONSTRAINT FK_extra FOREIGN KEY (extra) REFERENCES pedido (codigo)
                    USING DESCENDING INDEX ix_extra
                    """);
            db.execute("INSERT INTO item VALUES (1, 1, 10)");

            NexException emUso = assertThrows(NexException.class, () -> db.execute("ALTER TABLE pedido DROP PRIMARY KEY"));
            assertTrue(emUso.getMessage().contains("FK_item"));
            assertEquals("PK_pedido", table(db, "pedido").primaryConstraint());
            assertEquals("ix_pedido", table(db, "pedido").primaryIndex());
            assertTrue(table(db, "pedido").primaryDescending());
            assertThrows(NexException.class, () -> db.execute("DROP INDEX ix_pedido ON pedido"));

            db.execute("ALTER TABLE item DROP CONSTRAINT fk_item");
            TableDescription item = table(db, "item");
            assertEquals(List.of("FK_extra"), item.foreignKeys().stream().map(TableDescription.ForeignKey::name).toList());
            assertFalse(item.indexes().stream().anyMatch(index -> index.name().equals("FK_item")));
            assertTrue(item.indexes().stream().anyMatch(index -> index.name().equals("ix_extra") && index.descending()));
            db.execute("INSERT INTO item VALUES (2, 9, 10)");
            assertThrows(NexException.class, () -> db.execute("DELETE FROM pedido WHERE codigo = 10"));

            db.execute("ALTER TABLE pedido DROP CONSTRAINT PK_pedido");
            TableDescription pedido = table(db, "pedido");
            assertEquals("", pedido.primaryConstraint());
            assertEquals("", pedido.primaryIndex());
            assertEquals(0, pedido.columns().get(0).primaryKeyPosition());
            assertFalse(pedido.columns().get(0).nullable());
            assertEquals(List.of("UNQ1_pedido", "idx_nota"),
                    pedido.indexes().stream().map(TableDescription.TableIndex::name).toList());
            db.execute("INSERT INTO pedido VALUES (1, 'b', 11)");
            assertThrows(NexException.class, () -> db.execute("INSERT INTO pedido VALUES (NULL, 'c', 12)"));
            assertThrows(NexException.class, () -> db.execute("DROP INDEX ix_pedido ON pedido"));
            db.execute("CREATE INDEX ix_pedido ON pedido (id)");

            db.execute("ALTER TABLE item DROP FOREIGN KEY FK_extra");
            assertTrue(table(db, "item").foreignKeys().isEmpty());
            assertFalse(table(db, "item").indexes().stream().anyMatch(index -> index.name().equals("ix_extra")));
            db.execute("DELETE FROM pedido WHERE codigo = 10");
            db.execute("INSERT INTO pedido VALUES (1, 'c', 12)");
            db.execute("CREATE INDEX ix_extra ON item (extra)");

            NexException ausente = assertThrows(NexException.class,
                    () -> db.execute("ALTER TABLE item DROP CONSTRAINT FK_extra"));
            assertTrue(ausente.getMessage().contains("FK_extra"));
            assertThrows(NexException.class, () -> db.execute("ALTER TABLE pedido DROP PRIMARY KEY"));

            db.execute("BEGIN");
            db.execute("UPDATE item SET extra = 10 WHERE id = 1");
            NexException transacao = assertThrows(NexException.class,
                    () -> db.execute("ALTER TABLE item DROP CONSTRAINT PK_item"));
            assertTrue(transacao.getMessage().contains("transaction"));
            db.execute("ROLLBACK");
            assertEquals("PK_item", table(db, "item").primaryConstraint());

            db.execute("CREATE TABLE par (a INTEGER NOT NULL, b INTEGER NOT NULL, PRIMARY KEY (a, b))");
            db.execute("INSERT INTO par VALUES (1, 1)");
            db.execute("ALTER TABLE par DROP PRIMARY KEY");
            db.execute("INSERT INTO par VALUES (1, 1)");
            assertEquals("", table(db, "par").primaryConstraint());
            assertTrue(table(db, "par").indexes().isEmpty());

            db.execute("CREATE TABLE arvore (id INTEGER PRIMARY KEY, pai INTEGER)");
            db.execute("ALTER TABLE arvore ADD FOREIGN KEY (pai) REFERENCES arvore (id)");
            assertEquals("FK_arvore", table(db, "arvore").foreignKeys().get(0).name());
            assertThrows(NexException.class, () -> db.execute("ALTER TABLE arvore DROP PRIMARY KEY"));
            db.execute("ALTER TABLE arvore DROP FOREIGN KEY FK_arvore");
            db.execute("ALTER TABLE arvore DROP PRIMARY KEY");
            assertEquals("", table(db, "arvore").primaryConstraint());
            assertTrue(table(db, "arvore").foreignKeys().isEmpty());
            assertTrue(table(db, "arvore").indexes().isEmpty());
            assertFalse(table(db, "arvore").columns().get(0).nullable());

            db.execute("CREATE SCHEMA vendas");
            db.execute("CREATE TABLE vendas.nota (id INTEGER PRIMARY KEY)");
            db.execute("ALTER TABLE vendas.nota DROP CONSTRAINT PK_nota");
            TableDescription nota = db.describeTables().stream()
                    .filter(described -> described.schema().equals("vendas") && described.name().equals("nota"))
                    .findFirst().orElseThrow();
            assertEquals("", nota.primaryConstraint());
            assertTrue(nota.indexes().isEmpty());
        }
        try (Database db = open()) {
            assertEquals("", table(db, "pedido").primaryConstraint());
            assertTrue(table(db, "pedido").indexes().stream().anyMatch(index -> index.name().equals("idx_nota")));
            assertTrue(table(db, "pedido").indexes().stream().anyMatch(index -> index.name().equals("ix_pedido")));
            assertTrue(table(db, "pedido").indexes().stream().anyMatch(index -> index.name().equals("UNQ1_pedido")));
            assertEquals(0, table(db, "item").foreignKeys().size());
            assertEquals("PK_item", table(db, "item").primaryIndex());
            assertEquals(2, db.query("SELECT id FROM pedido WHERE id = 1").size());
            assertEquals(0, db.query("SELECT codigo FROM pedido WHERE codigo = 10").size());
            db.execute("DELETE FROM pedido WHERE nota = 'c'");
            db.execute("ALTER TABLE pedido ADD CONSTRAINT PK_pedido PRIMARY KEY (id)");
            assertThrows(NexException.class, () -> db.execute("INSERT INTO pedido VALUES (1, 'd', 13)"));
            assertEquals("PK_pedido", table(db, "pedido").primaryIndex());
            assertTrue(table(db, "arvore").indexes().isEmpty());
        }
    }

    @Test
    void uniqueUsaOProprioNomeESaiComOIndice() {
        try (Database db = open()) {
            db.execute("""
                    CREATE TABLE cliente (
                        id INTEGER PRIMARY KEY,
                        email VARCHAR(40) UNIQUE,
                        documento VARCHAR(20),
                        CONSTRAINT UQ_documento UNIQUE (documento)
                    )
                    """);
            TableDescription cliente = table(db, "cliente");
            assertEquals(List.of("PK_cliente", "UNQ1_cliente", "UQ_documento"),
                    cliente.indexes().stream().map(TableDescription.TableIndex::name).toList());
            db.execute("ALTER TABLE cliente ADD COLUMN extra INTEGER UNIQUE");
            assertTrue(table(db, "cliente").indexes().stream().anyMatch(index -> index.name().equals("UNQ2_cliente")));
            db.execute("ALTER TABLE cliente DROP COLUMN extra");
            assertTrue(cliente.indexes().get(1).unique());
            assertTrue(cliente.indexes().get(2).unique());
            db.execute("INSERT INTO cliente VALUES (1, 'a@x', 'd1')");
            assertThrows(NexException.class, () -> db.execute("INSERT INTO cliente VALUES (2, 'a@x', 'd2')"));
            assertThrows(NexException.class, () -> db.execute("INSERT INTO cliente VALUES (2, 'b@x', 'd1')"));
            assertThrows(NexException.class, () -> db.execute("DROP INDEX UNQ1_cliente ON cliente"));
            assertThrows(NexException.class, () -> db.execute("DROP INDEX UQ_documento ON cliente"));

            db.execute("CREATE TABLE filho (id INTEGER PRIMARY KEY, documento VARCHAR(20))");
            db.execute("ALTER TABLE filho ADD FOREIGN KEY (documento) REFERENCES cliente (documento)");
            NexException emUso = assertThrows(NexException.class,
                    () -> db.execute("ALTER TABLE cliente DROP CONSTRAINT UQ_documento"));
            assertTrue(emUso.getMessage().contains("FK_filho"));
            assertTrue(table(db, "cliente").indexes().stream().anyMatch(index -> index.name().equals("UQ_documento")));
            db.execute("ALTER TABLE filho DROP CONSTRAINT FK_filho");

            db.execute("ALTER TABLE cliente DROP UNIQUE UNQ1_cliente");
            db.execute("INSERT INTO cliente VALUES (2, 'a@x', 'd2')");
            assertFalse(table(db, "cliente").indexes().stream().anyMatch(index -> index.name().equals("UNQ1_cliente")));
            assertTrue(table(db, "cliente").indexes().stream().anyMatch(index -> index.name().equals("UQ_documento")));

            db.execute("ALTER TABLE cliente DROP CONSTRAINT UQ_documento");
            assertEquals(List.of("PK_cliente"),
                    table(db, "cliente").indexes().stream().map(TableDescription.TableIndex::name).toList());

            db.execute("INSERT INTO cliente VALUES (3, 'c@x', 'd3'), (4, 'e@x', 'd3')");
            assertThrows(NexException.class, () -> db.execute("ALTER TABLE cliente ADD UNIQUE (documento)"));
            assertEquals(List.of("PK_cliente"),
                    table(db, "cliente").indexes().stream().map(TableDescription.TableIndex::name).toList());
            db.execute("DELETE FROM cliente WHERE id = 4");
            db.execute("ALTER TABLE cliente ADD CONSTRAINT UQ_documento UNIQUE (documento)");
            assertTrue(table(db, "cliente").indexes().stream().anyMatch(index -> index.name().equals("UQ_documento") && index.unique()));
            assertThrows(NexException.class, () -> db.execute("INSERT INTO cliente VALUES (5, 'f@x', 'd3')"));

            db.execute("ALTER TABLE cliente ADD COLUMN codigo INTEGER UNIQUE");
            assertTrue(table(db, "cliente").indexes().stream().anyMatch(index -> index.name().equals("UNQ1_cliente")));
            db.execute("ALTER TABLE cliente DROP COLUMN codigo");
            assertFalse(table(db, "cliente").indexes().stream().anyMatch(index -> index.name().equals("UNQ1_cliente")));
        }
        try (Database db = open()) {
            assertEquals(List.of("PK_cliente", "UQ_documento"),
                    table(db, "cliente").indexes().stream().map(TableDescription.TableIndex::name).toList());
            assertThrows(NexException.class, () -> db.execute("INSERT INTO cliente VALUES (9, 'z@x', 'd3')"));
            db.execute("ALTER TABLE cliente DROP UNIQUE UQ_documento");
            db.execute("INSERT INTO cliente VALUES (9, 'z@x', 'd3')");
            assertEquals(List.of("PK_cliente"),
                    table(db, "cliente").indexes().stream().map(TableDescription.TableIndex::name).toList());
        }
    }

    @Test
    void segundoAcessoEhRecusadoEnquantoAberto() {
        try (Database primeiro = open()) {
            NexException erro = assertThrows(NexException.class, () -> open());
            assertTrue(erro.getMessage().contains("open"));
            assertTrue(primeiro.tables().isEmpty());
        }
        try (Database reaberto = open()) {
            assertTrue(reaberto.tables().isEmpty());
        }
    }

    @Test
    void fecharDesfazTransacaoAberta() {
        try (Database db = open()) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
            db.execute("BEGIN");
            db.execute("INSERT INTO t VALUES (1)");
        }
        try (Database db = open()) {
            assertEquals(0, db.query("SELECT * FROM t").size());
        }
    }

    @Test
    void tiposNumericosTempoERejeicaoDeText() {
        try (Database db = open()) {
            db.execute("""
                    CREATE TABLE medida (
                        id SMALLINT PRIMARY KEY AUTOINCREMENT,
                        grande BIGINT,
                        simples FLOAT,
                        preciso DOUBLE,
                        valor DECIMAL(8, 2),
                        hora TIME,
                        medio INTEGER
                    )
                    """);
            db.execute("""
                    INSERT INTO medida (grande, simples, preciso, valor, hora, medio) VALUES
                    (9223372036854775807, 1.5, 1.25, 10.5, '13:45:01', 2147483647),
                    (-1, -2.5, 0, 0.10, '08:00', -2147483648)
                    """);
            SqlResult rows = db.query("SELECT * FROM medida ORDER BY id");
            assertEquals(DataType.SMALLINT, rows.columns().get(0).type());
            assertEquals(DataType.BIGINT, rows.columns().get(1).type());
            assertEquals(DataType.FLOAT, rows.columns().get(2).type());
            assertEquals(DataType.DOUBLE, rows.columns().get(3).type());
            assertEquals(DataType.DECIMAL, rows.columns().get(4).type());
            assertEquals(DataType.TIME, rows.columns().get(5).type());
            assertEquals(DataType.INTEGER, rows.columns().get(6).type());
            Row first = rows.rows().get(0);
            assertEquals((short) 1, first.getShort("id"));
            assertEquals(9223372036854775807L, first.getLong("grande"));
            assertEquals(1.5f, first.getFloat("simples"), 0f);
            assertEquals(1.25d, first.getDouble("preciso"), 0d);
            assertEquals(0, first.getDecimal("valor").compareTo(new java.math.BigDecimal("10.50")));
            assertEquals(LocalTime.of(13, 45, 1), first.getTime("hora"));
            assertEquals(2147483647, first.getInt("medio"));
            assertEquals(-2147483648, rows.rows().get(1).getInt("medio"));
            assertEquals(LocalTime.of(8, 0), rows.rows().get(1).getTime("hora"));
            assertEquals(1, db.query("SELECT id FROM medida WHERE hora = '13:45:01'").size());
            assertThrows(NexException.class, () -> db.execute("CREATE TABLE ruim (nota TEXT)"));
            assertThrows(NexException.class, () -> db.execute("INSERT INTO medida (grande, simples, preciso, valor, hora) VALUES (1, 1, 1, 1, '99:00')"));
            assertThrows(NexException.class, () -> db.execute("INSERT INTO medida (id, grande, simples, preciso, valor, hora, medio) VALUES (40000, 1, 1, 1, 1, '00:00', 1)"));
            assertThrows(NexException.class, () -> db.execute("INSERT INTO medida (grande, simples, preciso, valor, hora, medio) VALUES (1, 1, 1, 1, '00:00', 2147483648)"));
        }
        try (Database db = open()) {
            Row reopened = db.query("SELECT simples, hora, id, medio FROM medida WHERE id = 1").rows().get(0);
            assertEquals(1.5f, reopened.getFloat("simples"), 0f);
            assertEquals(LocalTime.of(13, 45, 1), reopened.getTime("hora"));
            assertEquals((short) 1, reopened.getShort("id"));
            assertEquals(2147483647, reopened.getInt("medio"));
        }
    }

    @Test
    void criaIndiceConsultaEReabre() {
        try (Database db = open()) {
            db.execute("""
                    CREATE TABLE pessoa (
                        id INTEGER PRIMARY KEY,
                        nome VARCHAR(40),
                        email VARCHAR(40)
                    )
                    """);
            db.execute("""
                    INSERT INTO pessoa VALUES
                    (1, 'Ana', 'a@x'),
                    (2, 'Ana', 'b@x'),
                    (3, 'Bia', 'c@x')
                    """);
            assertThrows(NexException.class, () -> db.execute("CREATE UNIQUE INDEX idx_nome ON pessoa (nome)"));
            db.execute("CREATE INDEX idx_nome ON pessoa (nome)");
            db.execute("CREATE UNIQUE INDEX idx_email ON pessoa (email)");
            db.execute("CREATE INDEX IF NOT EXISTS idx_email ON pessoa (email)");
            assertThrows(NexException.class, () -> db.execute("CREATE INDEX idx_email ON pessoa (email)"));
            assertThrows(NexException.class, () -> db.execute("INSERT INTO pessoa VALUES (4, 'Leo', 'a@x')"));
            assertThrows(NexException.class, () -> db.execute("UPDATE pessoa SET email = 'a@x' WHERE id = 3"));
            SqlResult anas = db.query("SELECT id FROM pessoa WHERE nome = ? ORDER BY id", "Ana");
            assertEquals(2, anas.size());
            assertEquals(1, anas.rows().get(0).getInt("id"));
            assertEquals(2, anas.rows().get(1).getInt("id"));
            assertEquals(2, db.query("SELECT id FROM pessoa WHERE nome = 'Ana' AND email = 'b@x'").rows().get(0).getInt("id"));
            db.execute("CREATE INDEX idx_nome_email ON pessoa (nome, email)");
            assertEquals(1, db.query("SELECT id FROM pessoa WHERE nome = 'Ana' AND email = 'a@x'").rows().get(0).getInt("id"));
            List<TableDescription.TableIndex> indexes = db.describeTables().get(0).indexes();
            assertEquals(List.of("PK_pessoa", "idx_nome", "idx_email", "idx_nome_email"),
                    indexes.stream().map(TableDescription.TableIndex::name).toList());
            assertFalse(indexes.get(1).unique());
            assertTrue(indexes.get(2).unique());
            db.execute("ALTER TABLE pessoa ADD COLUMN foto BLOB");
            assertThrows(NexException.class, () -> db.execute("CREATE INDEX idx_blob ON pessoa (foto)"));
            db.execute("BEGIN");
            db.execute("UPDATE pessoa SET nome = 'Ana' WHERE id = 1");
            assertThrows(NexException.class, () -> db.execute("CREATE INDEX idx_id ON pessoa (id)"));
            db.execute("ROLLBACK");
        }
        try (Database db = open()) {
            assertEquals(3, db.query("SELECT id FROM pessoa WHERE nome = 'Bia' OR nome = 'Ana'").size());
            assertThrows(NexException.class, () ->
                    db.execute("INSERT INTO pessoa (id, nome, email) VALUES (4, 'Leo', 'c@x')"));
            db.execute("DROP INDEX idx_nome ON pessoa");
            db.execute("DROP INDEX IF EXISTS idx_nome ON pessoa");
            assertThrows(NexException.class, () -> db.execute("DROP INDEX idx_nome ON pessoa"));
            db.execute("ALTER TABLE pessoa DROP COLUMN nome");
            List<String> restantes = db.describeTables().get(0).indexes().stream()
                    .map(TableDescription.TableIndex::name).toList();
            assertEquals(List.of("PK_pessoa", "idx_email"), restantes);
            assertEquals(1, db.query("SELECT id FROM pessoa WHERE email = 'a@x'").rows().get(0).getInt("id"));
        }
    }

    @Test
    void indiceEmPaginasSobreviveReabertura() throws Exception {
        try (Database db = open()) {
            db.execute("CREATE TABLE item (id INTEGER, nome VARCHAR(40), grupo INTEGER)");
            for (int i = 0; i < 500; i++) {
                db.execute("INSERT INTO item VALUES (?, ?, ?)", i, "n" + (i % 20), i % 7);
            }
            db.execute("ALTER TABLE item ADD PRIMARY KEY (id)");
            db.execute("CREATE INDEX idx_grupo ON item (grupo)");
            assertEquals(250, db.query("SELECT id FROM item WHERE id = 250").rows().get(0).getInt("id"));
            assertEquals(71, db.query("SELECT id FROM item WHERE grupo = 3").size());
            assertThrows(NexException.class, () -> db.execute("INSERT INTO item VALUES (1, 'novo', 1)"));
        }
        byte[] gravado = Files.readAllBytes(arquivo());
        ByteBuffer magic = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(Format.INDEX_MAGIC);
        assertTrue(indexOf(gravado, magic.array()) >= 0);
        try (Database db = open()) {
            assertEquals(250, db.query("SELECT id FROM item WHERE id = 250").rows().get(0).getInt("id"));
            assertEquals(71, db.query("SELECT id FROM item WHERE grupo = 3").size());
            assertThrows(NexException.class, () -> db.execute("INSERT INTO item VALUES (250, 'outro', 0)"));
            db.execute("DELETE FROM item WHERE id = 10");
            assertEquals(0, db.query("SELECT id FROM item WHERE id = 10").size());
            db.execute("INSERT INTO item VALUES (10, 'n10', 1)");
            assertEquals(10, db.query("SELECT id FROM item WHERE id = 10").rows().get(0).getInt("id"));
            db.execute("UPDATE item SET grupo = 4 WHERE id = 11");
            assertEquals(4, db.query("SELECT grupo FROM item WHERE id = 11").rows().get(0).getInt("grupo"));
            db.execute("DROP INDEX idx_grupo ON item");
            assertEquals(500, db.query("SELECT id FROM item WHERE grupo = 3 OR grupo <> 3").size());
        }
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        for (int i = 0; i <= data.length - pattern.length; i++) {
            boolean found = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void arquivoUnicoUsuarioETabelasDeSistema() {
        NexException semSenha = assertThrows(NexException.class, () -> Database.open(arquivo(), "dba", ""));
        assertTrue(semSenha.getMessage().contains("password"));
        assertThrows(NexException.class, () -> Database.open(dir, "dba", "dba"));
        try (Database db = open()) {
            assertTrue(Files.isRegularFile(arquivo()));
            assertTrue(db.tables().isEmpty());
            assertEquals("DBA", db.session().user());
            assertEquals("NEXORA", db.query("SELECT NEX$DATABASE_NAME FROM NEX$DATABASE").rows().get(0).getString(0));
            assertEquals(8192, db.pageSize());
            assertEquals(8192, db.query("SELECT NEX$PAGE_SIZE FROM NEX$DATABASE").rows().get(0).getInt("NEX$PAGE_SIZE"));
            assertEquals("bcrypt", db.query("SELECT NEX$VALUE FROM NEX$CONFIG WHERE NEX$KEY = 'Auth.Hash'").rows().get(0).getString(0));
            String hash = db.query("SELECT NEX$PASSWORD FROM NEX$USERS WHERE NEX$USER_NAME = 'DBA'").rows().get(0).getString(0);
            assertTrue(hash.startsWith("$2a$"));
            assertEquals(60, hash.length());
            assertEquals(1, db.query("SELECT NEX$ATTACHMENT_ID FROM NEX$CONNECTIONS WHERE NEX$STATE = 'ACTIVE'").size());
            Row origem = db.query("""
                    SELECT NEX$REMOTE_PROTOCOL, NEX$REMOTE_ADDRESS, NEX$REMOTE_OS_USER
                    FROM NEX$CONNECTIONS
                    """).rows().get(0);
            assertEquals("local", origem.getString("NEX$REMOTE_PROTOCOL"));
            assertNull(origem.getString("NEX$REMOTE_ADDRESS"));
            assertEquals(System.getProperty("user.name"), origem.getString("NEX$REMOTE_OS_USER"));
            assertThrows(NexException.class, () -> db.execute("DROP TABLE NEX$USERS"));
            assertThrows(NexException.class, () -> db.execute("DELETE FROM NEX$USERS"));
            db.execute("INSERT INTO NEX$USERS (NEX$USER_NAME, NEX$PASSWORD) VALUES ('ana', 'ana')");
            Session ana = db.login("ana", "ana");
            assertFalse(ana.admin());
            assertThrows(NexException.class, () -> db.execute(ana, "DELETE FROM NEX$CONFIG"));
            db.logout(ana);
        }
        assertThrows(NexException.class, () -> Database.open(arquivo(), "dba", "errada"));
        try (Database db = open()) {
            assertEquals(0, db.query("SELECT NEX$ATTACHMENT_ID FROM NEX$CONNECTIONS WHERE NEX$STATE = 'CLOSED'").size());
            assertEquals(1, db.query("SELECT NEX$ATTACHMENT_ID FROM NEX$CONNECTIONS WHERE NEX$STATE = 'ACTIVE'").size());
        }
    }

    @Test
    void autorizacaoDoUsuarioNaTabela() {
        try (Database db = open()) {
            db.execute("CREATE TABLE segredo (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            db.execute("INSERT INTO segredo VALUES (1, 'x')");
            db.execute("INSERT INTO NEX$USERS (NEX$USER_NAME, NEX$PASSWORD) VALUES ('ana', 'ana'), ('bia', 'bia')");
            assertThrows(NexException.class, () -> db.execute("INSERT INTO NEX$PRIVILEGES VALUES ('ANA', 'segredo', 'SELECT', TRUE, 'DBA')"));
            Session ana = db.login("ana", "ana");
            Session bia = db.login("bia", "bia");
            assertThrows(NexException.class, () -> db.execute(ana, "SELECT nome FROM segredo"));
            db.execute("GRANT SELECT ON segredo TO ana");
            assertEquals("x", db.execute(ana, "SELECT nome FROM segredo").rows().get(0).getString(0));
            assertThrows(NexException.class, () -> db.execute(ana, "INSERT INTO segredo VALUES (2, 'y')"));
            assertThrows(NexException.class, () -> db.execute(ana, "GRANT SELECT ON segredo TO bia"));
            db.execute("GRANT INSERT ON segredo TO ana WITH GRANT OPTION");
            db.execute(ana, "INSERT INTO segredo VALUES (2, 'y')");
            db.execute(ana, "GRANT INSERT ON segredo TO bia");
            db.execute(bia, "INSERT INTO segredo VALUES (3, 'z')");
            assertThrows(NexException.class, () -> db.execute(bia, "SELECT nome FROM segredo"));
            db.execute("GRANT SELECT ON segredo TO PUBLIC");
            assertEquals(3, db.execute(bia, "SELECT id FROM segredo").size());
            db.execute("REVOKE ALL ON segredo FROM PUBLIC");
            assertThrows(NexException.class, () -> db.execute(bia, "SELECT id FROM segredo"));
            db.execute(ana, "CREATE TABLE nota (id INTEGER PRIMARY KEY)");
            db.execute(ana, "INSERT INTO nota VALUES (1)");
            db.execute("REVOKE ALL ON nota FROM ana");
            db.execute(ana, "INSERT INTO nota VALUES (2)");
            assertThrows(NexException.class, () -> db.execute(ana, "DROP TABLE segredo"));
            db.execute(ana, "DROP TABLE nota");
            db.logout(bia);
            db.logout(ana);
        }
    }

    @Test
    void geradorSobreviveAoRollback() {
        try (Database db = open()) {
            db.execute("CREATE TABLE item (id INTEGER)");
            db.execute("CREATE GENERATOR gen_item");
            db.execute("CREATE SEQUENCE seq_item START WITH 10 INCREMENT BY 2");
            assertThrows(NexException.class, () -> db.execute("""
                    INSERT INTO NEX$GENERATORS (
                        NEX$GENERATOR_NAME, NEX$GENERATOR_ID, NEX$INITIAL_VALUE, NEX$INCREMENT, NEX$CURRENT_VALUE, NEX$OWNER_NAME
                    ) VALUES ('x', 9, 0, 1, 0, 'DBA')
                    """));
            db.execute("BEGIN");
            db.execute("INSERT INTO item (id) VALUES (GEN_ID(gen_item, 1))");
            db.execute("ROLLBACK");
            assertEquals(0, db.query("SELECT id FROM item").size());
            assertEquals(2L, db.query("SELECT GEN_ID(gen_item, 1) AS n FROM NEX$DATABASE").rows().get(0).getLong("n"));
            assertEquals(2L, db.query("SELECT GEN_ID(gen_item, 0) AS n FROM NEX$DATABASE").rows().get(0).getLong("n"));
            db.execute("SET GENERATOR gen_item TO 100");
            assertEquals(101L, db.query("SELECT GEN_ID(gen_item, 1) AS n FROM NEX$DATABASE").rows().get(0).getLong("n"));
            assertEquals(10L, db.query("SELECT NEXT VALUE FOR seq_item AS n FROM NEX$DATABASE").rows().get(0).getLong("n"));
            assertEquals(12L, db.query("SELECT NEXT VALUE FOR seq_item AS n FROM NEX$DATABASE").rows().get(0).getLong("n"));
            db.execute("ALTER SEQUENCE seq_item RESTART WITH 20");
            assertEquals(20L, db.query("SELECT NEXT VALUE FOR seq_item AS n FROM NEX$DATABASE").rows().get(0).getLong("n"));
        }
        try (Database db = open()) {
            assertEquals(22L, db.query("SELECT NEXT VALUE FOR seq_item AS n FROM NEX$DATABASE").rows().get(0).getLong("n"));
            db.execute("DROP SEQUENCE seq_item");
            db.execute("DROP GENERATOR IF EXISTS gen_item");
            assertThrows(NexException.class, () -> db.query("SELECT GEN_ID(gen_item, 1) AS n FROM NEX$DATABASE"));
        }
    }

    @Test
    void procedureETrigger() {
        try (Database db = open()) {
            db.execute("CREATE TABLE item (id INTEGER NOT NULL PRIMARY KEY, nome VARCHAR(20) NOT NULL)");
            db.execute("CREATE TABLE log (id INTEGER, texto VARCHAR(40))");
            db.execute("CREATE GENERATOR gen_item");
            db.execute("""
                    CREATE TRIGGER item_bi FOR item
                    ACTIVE BEFORE INSERT POSITION 0
                    AS
                    BEGIN
                      IF (NEW.id IS NULL) THEN
                        NEW.id = GEN_ID(gen_item, 1);
                    END
                    """);
            db.execute("""
                    CREATE TRIGGER item_nome FOR item
                    BEFORE INSERT POSITION 1
                    AS
                    BEGIN
                      IF (NEW.nome = 'troca') THEN
                        NEW.nome = 'pronto';
                    END
                    """);
            db.execute("""
                    CREATE TRIGGER item_fim FOR item
                    BEFORE INSERT POSITION 2
                    AS
                    BEGIN
                      IF (NEW.nome = 'pronto') THEN
                        NEW.nome = 'final';
                    END
                    """);
            db.execute("""
                    CREATE TRIGGER item_ai FOR item
                    AFTER INSERT POSITION 0
                    AS
                    BEGIN
                      INSERT INTO log (id, texto) VALUES (NEW.id, NEW.nome);
                    END
                    """);
            db.execute("""
                    CREATE TRIGGER item_bu FOR item
                    BEFORE UPDATE
                    AS
                    BEGIN
                      IF (NEW.nome IS NULL) THEN
                        NEW.nome = OLD.nome;
                    END
                    """);
            db.execute("INSERT INTO item (nome) VALUES ('Ana')");
            db.execute("INSERT INTO item (nome) VALUES ('troca')");
            assertEquals(1L, db.query("SELECT id FROM item WHERE nome = 'Ana'").rows().get(0).getLong(0));
            assertEquals("final", db.query("SELECT nome FROM item WHERE id = 2").rows().get(0).getString(0));
            assertEquals("Ana", db.query("SELECT texto FROM log WHERE id = 1").rows().get(0).getString(0));
            assertEquals("final", db.query("SELECT texto FROM log WHERE id = 2").rows().get(0).getString(0));
            db.execute("UPDATE item SET nome = NULL WHERE id = 1");
            assertEquals("Ana", db.query("SELECT nome FROM item WHERE id = 1").rows().get(0).getString(0));
            db.execute("BEGIN");
            db.execute("INSERT INTO item (nome) VALUES ('temp')");
            db.execute("ROLLBACK");
            assertEquals(0, db.query("SELECT id FROM item WHERE nome = 'temp'").size());
            assertEquals(0, db.query("SELECT id FROM log WHERE id = 3").size());
            db.execute("INSERT INTO item (nome) VALUES ('depois')");
            assertEquals(4L, db.query("SELECT id FROM item WHERE nome = 'depois'").rows().get(0).getLong(0));
            db.execute("ALTER TRIGGER item_ai INACTIVE");
            db.execute("INSERT INTO item (nome) VALUES ('Bia')");
            assertEquals(0, db.query("SELECT id FROM log WHERE texto = 'Bia'").size());
            db.execute("""
                    CREATE PROCEDURE soma (a INTEGER, b INTEGER)
                    RETURNS (resultado INTEGER)
                    AS
                    BEGIN
                      resultado = a + b;
                      SUSPEND;
                    END
                    """);
            assertEquals(5L, db.query("EXECUTE PROCEDURE soma(2, 3)").rows().get(0).getLong("resultado"));
            assertEquals(5L, db.query("SELECT resultado FROM soma(2, 3)").rows().get(0).getLong(0));
            db.execute("""
                    CREATE PROCEDURE serie (ate INTEGER)
                    RETURNS (n INTEGER)
                    AS
                    DECLARE VARIABLE extra INTEGER;
                    BEGIN
                      n = 1;
                      WHILE (n <= ate) DO
                      BEGIN
                        SUSPEND;
                        n = n + 1;
                      END
                    END
                    """);
            SqlResult serie = db.query("SELECT n FROM serie(3) ORDER BY n");
            assertEquals(3, serie.size());
            assertEquals(1L, serie.rows().get(0).getLong(0));
            assertEquals(3L, serie.rows().get(2).getLong(0));
            db.execute("""
                    CREATE PROCEDURE copia
                    AS
                    DECLARE VARIABLE x INTEGER;
                    BEGIN
                      SELECT id INTO x FROM item WHERE nome = 'Ana';
                      INSERT INTO log (id, texto) VALUES (:x, 'copia');
                    END
                    """);
            db.execute("EXECUTE PROCEDURE copia");
            assertEquals("copia", db.query("SELECT texto FROM log WHERE texto = 'copia'").rows().get(0).getString(0));
            db.execute("""
                    CREATE PROCEDURE falha
                    AS
                    BEGIN
                      EXCEPTION 'nao pode';
                    END
                    """);
            NexException erro = assertThrows(NexException.class, () -> db.execute("EXECUTE PROCEDURE falha"));
            assertTrue(erro.getMessage().contains("nao pode"));
            assertThrows(NexException.class, () -> db.execute("""
                    INSERT INTO NEX$PROCEDURES (NEX$PROCEDURE_NAME, NEX$PROCEDURE_SOURCE, NEX$OWNER_NAME)
                    VALUES ('x', 'BEGIN END', 'DBA')
                    """));
            db.execute("DROP TABLE item");
            assertEquals(0, db.query("SELECT NEX$TRIGGER_NAME FROM NEX$TRIGGERS WHERE NEX$RELATION_NAME = 'item'").size());
        }
    }

    @Test
    void cursorNoEstiloFirebird() {
        try (Database db = open()) {
            db.execute("CREATE TABLE item (id INTEGER NOT NULL PRIMARY KEY, nome VARCHAR(20) NOT NULL)");
            db.execute("CREATE TABLE log (texto VARCHAR(20))");
            db.execute("INSERT INTO item VALUES (1, 'Ana'), (2, 'Bia'), (3, 'Caio')");
            db.execute("""
                    CREATE PROCEDURE a_partir (minimo INTEGER)
                    RETURNS (id INTEGER, nome VARCHAR(20))
                    AS
                    DECLARE c CURSOR FOR (SELECT id, nome FROM item WHERE id >= :minimo ORDER BY id);
                    BEGIN
                      OPEN c;
                      FETCH c INTO id, nome;
                      WHILE (ROW_COUNT > 0) DO
                      BEGIN
                        SUSPEND;
                        FETCH c INTO id, nome;
                      END
                      CLOSE c;
                    END
                    """);
            SqlResult filtrado = db.query("SELECT id, nome FROM a_partir(2)");
            assertEquals(2, filtrado.size());
            assertEquals(2L, filtrado.rows().get(0).getLong("id"));
            assertEquals("Bia", filtrado.rows().get(0).getString("nome"));
            assertEquals("Caio", filtrado.rows().get(1).getString("nome"));
            db.execute("""
                    CREATE PROCEDURE nomes
                    RETURNS (nome VARCHAR(20))
                    AS
                    BEGIN
                      FOR SELECT nome FROM item ORDER BY id
                      INTO nome
                      DO SUSPEND;
                    END
                    """);
            SqlResult nomes = db.query("SELECT nome FROM nomes()");
            assertEquals(3, nomes.size());
            assertEquals("Ana", nomes.rows().get(0).getString(0));
            assertEquals("Caio", nomes.rows().get(2).getString(0));
            db.execute("""
                    CREATE PROCEDURE fechado
                    AS
                    DECLARE VARIABLE id INTEGER;
                    DECLARE c CURSOR FOR (SELECT id FROM item);
                    BEGIN
                      FETCH c INTO id;
                    END
                    """);
            NexException fechado = assertThrows(NexException.class, () -> db.execute("EXECUTE PROCEDURE fechado"));
            assertTrue(fechado.getMessage().contains("is not open"));
            db.execute("""
                    CREATE PROCEDURE duas
                    AS
                    DECLARE VARIABLE id INTEGER;
                    DECLARE c CURSOR FOR (SELECT id FROM item);
                    BEGIN
                      OPEN c;
                      OPEN c;
                    END
                    """);
            NexException duas = assertThrows(NexException.class, () -> db.execute("EXECUTE PROCEDURE duas"));
            assertTrue(duas.getMessage().contains("is already open"));
            db.execute("""
                    CREATE TRIGGER item_ai FOR item
                    AFTER INSERT
                    AS
                    DECLARE VARIABLE nome VARCHAR(20);
                    DECLARE c CURSOR FOR (SELECT nome FROM item WHERE id = NEW.id);
                    BEGIN
                      OPEN c;
                      FETCH c INTO nome;
                      CLOSE c;
                      INSERT INTO log (texto) VALUES (:nome);
                    END
                    """);
            db.execute("INSERT INTO item VALUES (4, 'Duda')");
            assertEquals("Duda", db.query("SELECT texto FROM log").rows().get(0).getString(0));
        }
    }

    @Test
    void viewNoEstiloFirebird() {
        try (Database db = open()) {
            db.execute("CREATE TABLE item (id INTEGER PRIMARY KEY, nome VARCHAR(20), ativo BOOLEAN)");
            db.execute("INSERT INTO item VALUES (1, 'Ana', TRUE), (2, 'Bia', FALSE), (3, 'Caio', TRUE)");
            db.execute("CREATE VIEW ativo (pessoa) AS SELECT nome FROM item WHERE ativo = TRUE");
            SqlResult filtrado = db.query("SELECT a.pessoa FROM ativo a WHERE a.pessoa <> 'Caio' ORDER BY pessoa");
            assertEquals(1, filtrado.size());
            assertEquals("Ana", filtrado.rows().get(0).getString(0));
            assertEquals(2L, db.query("SELECT COUNT(*) FROM ativo").rows().get(0).getLong(0));
            db.execute("CREATE VIEW nomes AS SELECT pessoa FROM ativo");
            assertEquals(2, db.query("SELECT * FROM nomes").size());
            db.execute("""
                    CREATE PROCEDURE primeira
                    RETURNS (pessoa VARCHAR(20))
                    AS
                    DECLARE c CURSOR FOR (SELECT pessoa FROM ativo ORDER BY pessoa);
                    BEGIN
                      OPEN c;
                      FETCH c INTO pessoa;
                      SUSPEND;
                      CLOSE c;
                    END
                    """);
            assertEquals("Ana", db.query("SELECT pessoa FROM primeira()").rows().get(0).getString(0));
            NexException gravar = assertThrows(NexException.class,
                    () -> db.execute("INSERT INTO ativo (pessoa) VALUES ('Lia')"));
            assertTrue(gravar.getMessage().contains("read-only"));
            assertThrows(NexException.class,
                    () -> db.execute("INSERT INTO NEX$VIEWS (NEX$VIEW_NAME, NEX$VIEW_SOURCE, NEX$RELATION_NAME, NEX$COLUMNS, NEX$OWNER_NAME) VALUES ('x', 'SELECT 1', 'item', 'x', 'DBA')"));
            NexException remover = assertThrows(NexException.class, () -> db.execute("DROP TABLE item"));
            assertTrue(remover.getMessage().contains("depends"));
            db.execute("INSERT INTO NEX$USERS (NEX$USER_NAME, NEX$PASSWORD) VALUES ('ana', 'ana')");
            Session ana = db.login("ana", "ana");
            assertThrows(NexException.class, () -> db.execute(ana, "SELECT pessoa FROM ativo"));
            db.execute("GRANT SELECT ON VIEW ativo TO ana");
            assertEquals("Ana", db.execute(ana, "SELECT pessoa FROM ativo WHERE pessoa = 'Ana'").rows().get(0).getString(0));
            List<TableDescription> views = db.describeViews();
            assertEquals(2, views.size());
            TableDescription ativo = views.get(0);
            assertEquals("ativo", ativo.name());
            assertEquals("pessoa", ativo.columns().get(0).name());
            assertEquals(20, ativo.columns().get(0).length());
            db.execute("CREATE VIEW IF NOT EXISTS ativo (pessoa) AS SELECT nome FROM item");
            db.execute("DROP VIEW nomes");
            db.execute("DROP VIEW ativo");
            db.execute("DROP VIEW IF EXISTS ativo");
            db.execute("DROP TABLE item");
        }
        try (Database db = open()) {
            db.execute("CREATE TABLE item (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            db.execute("INSERT INTO item VALUES (1, 'Ana')");
            db.execute("CREATE VIEW item_nome AS SELECT nome FROM item");
        }
        try (Database db = open()) {
            assertEquals("Ana", db.query("SELECT nome FROM item_nome").rows().get(0).getString(0));
        }
    }

    @Test
    void contextosNoEstiloFirebird() {
        try (Database db = open()) {
            SqlResult mesmoInstante = db.query("""
                    SELECT CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, LOCALTIMESTAMP, CURRENT_TIME, LOCALTIME, CURRENT_DATE
                    FROM NEX$DATABASE
                    """);
            Row instante = mesmoInstante.rows().get(0);
            LocalDateTime carimbo = (LocalDateTime) instante.get(0);
            assertEquals(carimbo, instante.get(1));
            assertEquals(carimbo, instante.get(2));
            assertEquals(0, carimbo.getNano() % 1_000_000);
            LocalTime hora = (LocalTime) instante.get(3);
            assertEquals(hora, instante.get(4));
            assertEquals(0, hora.getNano());
            assertEquals(carimbo.toLocalDate(), instante.get(5));
            Row solto = db.query("SELECT CURRENT_DATE, 1 + 1 AS dois").rows().get(0);
            assertEquals(carimbo.toLocalDate(), solto.get(0));
            assertEquals(2, solto.getInt("dois"));
            assertEquals(0, db.query("SELECT CURRENT_DATE WHERE 1 = 0").size());
            assertEquals(1L, db.query("SELECT COUNT(*) AS total").rows().get(0).getLong("total"));
            assertThrows(NexException.class, () -> db.query("SELECT *"));
            assertThrows(NexException.class, () -> db.execute("CREATE VIEW hoje AS SELECT CURRENT_DATE"));
            LocalTime fina = (LocalTime) db.query("SELECT CURRENT_TIME(3) FROM NEX$DATABASE").rows().get(0).get(0);
            assertEquals(0, fina.getNano() % 1_000_000);
            assertThrows(NexException.class, () -> db.execute("SELECT CURRENT_TIME(4) FROM NEX$DATABASE"));
            assertEquals("DBA", db.query("SELECT CURRENT_USER FROM NEX$DATABASE").rows().get(0).getString(0));
            assertEquals("DBA", db.query("SELECT USER FROM NEX$DATABASE").rows().get(0).getString(0));
            assertEquals("NONE", db.query("SELECT CURRENT_ROLE FROM NEX$DATABASE").rows().get(0).getString(0));
            long primeira = db.query("SELECT CURRENT_TRANSACTION FROM NEX$DATABASE").rows().get(0).getLong(0);
            long segunda = db.query("SELECT CURRENT_TRANSACTION FROM NEX$DATABASE").rows().get(0).getLong(0);
            assertEquals(primeira + 1, segunda);
            db.execute("BEGIN");
            long aberta = db.query("SELECT CURRENT_TRANSACTION FROM NEX$DATABASE").rows().get(0).getLong(0);
            assertEquals(aberta, db.query("SELECT CURRENT_TRANSACTION FROM NEX$DATABASE").rows().get(0).getLong(0));
            db.execute("ROLLBACK");
            assertEquals(aberta + 1, db.query("SELECT CURRENT_TRANSACTION FROM NEX$DATABASE").rows().get(0).getLong(0));
            db.execute("INSERT INTO NEX$USERS (NEX$USER_NAME, NEX$PASSWORD) VALUES ('ana', 'ana')");
            Session ana = db.login("ana", "ana");
            assertEquals(ana.attachmentId, db.execute(ana, "SELECT CURRENT_CONNECTION FROM NEX$DATABASE").rows().get(0).getLong(0));
            db.execute("CREATE TABLE item (id INTEGER PRIMARY KEY)");
            db.execute("INSERT INTO item VALUES (1)");
            db.execute("CREATE VIEW quem AS SELECT CURRENT_USER AS quem FROM item");
            db.execute("GRANT SELECT ON VIEW quem TO ana");
            assertEquals("ANA", db.execute(ana, "SELECT quem FROM quem").rows().get(0).getString(0));
            db.execute("""
                    CREATE PROCEDURE marca
                    RETURNS (a TIMESTAMP, b TIMESTAMP)
                    AS
                    BEGIN
                      a = CURRENT_TIMESTAMP;
                      b = CURRENT_TIMESTAMP;
                      SUSPEND;
                    END
                    """);
            SqlResult marca = db.query("SELECT a, b FROM marca()");
            assertEquals(marca.rows().get(0).get(0), marca.rows().get(0).get(1));
            db.execute("CREATE TABLE log (id INTEGER PRIMARY KEY, quando TIMESTAMP, dia DATE)");
            db.execute("INSERT INTO log VALUES (1, CURRENT_TIMESTAMP, CURRENT_DATE)");
            Row gravado = db.query("SELECT quando, dia FROM log").rows().get(0);
            assertEquals(((LocalDateTime) gravado.get(0)).toLocalDate(), gravado.get(1));
        }
    }

    @Test
    void papeisEDropConnection() {
        try (Database db = open()) {
            assertEquals("NONE", db.query("SELECT CURRENT_ROLE FROM NEX$DATABASE").rows().get(0).getString(0));
            db.execute("CREATE TABLE item (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            db.execute("INSERT INTO item VALUES (1, 'Ana')");
            db.execute("CREATE ROLE vendas");
            db.execute("CREATE ROLE IF NOT EXISTS vendas");
            db.execute("GRANT SELECT ON item TO vendas");
            assertThrows(NexException.class, () -> db.execute(
                    "INSERT INTO NEX$ROLES (NEX$ROLE_NAME, NEX$OWNER_NAME) VALUES ('x', 'DBA')"));
            db.execute("INSERT INTO NEX$USERS (NEX$USER_NAME, NEX$PASSWORD) VALUES ('ana', 'ana'), ('bia', 'bia')");
            assertThrows(NexException.class, () -> db.login("bia", "bia", "vendas"));
            db.execute("GRANT vendas TO ana WITH ADMIN OPTION");
            Session ana = db.login("ana", "ana");
            assertEquals("NONE", db.execute(ana, "SELECT CURRENT_ROLE FROM NEX$DATABASE").rows().get(0).getString(0));
            assertThrows(NexException.class, () -> db.execute(ana, "SELECT nome FROM item"));
            db.execute(ana, "GRANT vendas TO bia");
            Session anaVendas = db.login("ana", "ana", "vendas");
            assertEquals("VENDAS", anaVendas.role());
            assertEquals("VENDAS", db.execute(anaVendas, "SELECT CURRENT_ROLE FROM NEX$DATABASE").rows().get(0).getString(0));
            assertEquals("Ana", db.execute(anaVendas, "SELECT nome FROM item").rows().get(0).getString(0));
            Session biaVendas = db.login("bia", "bia", "vendas");
            assertEquals("Ana", db.execute(biaVendas, "SELECT nome FROM item").rows().get(0).getString(0));
            Session dbaVendas = db.login("dba", "dba", "vendas");
            assertEquals("VENDAS", dbaVendas.role());
            long id = anaVendas.attachmentId;
            db.execute("DROP CONNECTION " + id);
            NexException derrubada = assertThrows(NexException.class,
                    () -> db.execute(anaVendas, "SELECT CURRENT_USER FROM NEX$DATABASE"));
            assertTrue(derrubada.getMessage().contains("closed"));
            assertEquals(0, db.query(
                    "SELECT NEX$ATTACHMENT_ID FROM NEX$CONNECTIONS WHERE NEX$ATTACHMENT_ID = " + id).size());
            assertThrows(NexException.class, () -> db.execute("DROP CONNECTION " + id));
            assertThrows(NexException.class, () -> db.execute(ana, "DROP CONNECTION " + db.session().attachmentId));
            db.execute("DROP ROLE vendas");
            assertThrows(NexException.class, () -> db.login("bia", "bia", "vendas"));
            db.execute("DROP CONNECTION CURRENT_CONNECTION");
            assertThrows(NexException.class, () -> db.query("SELECT CURRENT_USER FROM NEX$DATABASE"));
        }
    }

    @Test
    void criaBancoComPaginaESenhaDoDba() throws Exception {
        Path arquivo = dir.resolve("loja.db");
        Database.create(arquivo, new DatabaseOptions(16384, "dba", "segredo", "Loja", "Loja central", "WIN1252"));
        assertEquals(0, Files.size(arquivo) % 16384);
        byte[] header = Files.readAllBytes(arquivo);
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        assertEquals(1, buffer.getShort(Format.VERSION_OFFSET) & 0xFFFF);
        assertEquals(16384, buffer.getInt(64));
        assertThrows(NexException.class, () -> Database.open(arquivo, "dba", "dba"));
        try (Database db = Database.open(arquivo, "DBA", "segredo")) {
            assertEquals(16384, db.pageSize());
            Row banco = db.query("""
                    SELECT NEX$DATABASE_NAME, NEX$DESCRIPTION, NEX$OWNER_NAME, NEX$PAGE_SIZE, NEX$FORMAT
                    FROM NEX$DATABASE
                    """).rows().get(0);
            assertEquals("Loja", banco.getString("NEX$DATABASE_NAME"));
            assertEquals("Loja central", banco.getString("NEX$DESCRIPTION"));
            assertEquals("DBA", banco.getString("NEX$OWNER_NAME"));
            assertEquals(16384, banco.getInt("NEX$PAGE_SIZE"));
            assertEquals(1, banco.getInt("NEX$FORMAT"));
            assertEquals("WIN1252", db.query(
                    "SELECT NEX$VALUE FROM NEX$CONFIG WHERE NEX$KEY = 'Default.CharacterSet'").rows().get(0).getString(0));
            db.execute("CREATE TABLE item (id INTEGER PRIMARY KEY, nome VARCHAR(40))");
            for (int i = 0; i < 40; i++) {
                db.execute("INSERT INTO item (id, nome) VALUES (?, ?)", i, "n" + i);
            }
        }
        assertEquals(0, Files.size(arquivo) % 16384);
        try (Database db = Database.open(arquivo, "dba", "segredo")) {
            assertEquals(40L, db.query("SELECT COUNT(*) FROM item").rows().get(0).getLong(0));
        }
        assertThrows(NexException.class, () -> Database.create(arquivo, DatabaseOptions.defaults()));
        assertThrows(NexException.class, () -> new DatabaseOptions(1024, "dba", "dba", "x", "x", "UTF8"));
    }

    @Test
    void abrirNaoCriaArquivo() throws Exception {
        Path ausente = dir.resolve("ausente.db");
        NexException aberto = assertThrows(NexException.class, () -> Database.open(ausente, "dba", "dba"));
        assertTrue(aberto.getMessage().contains("does not exist"));
        NexException anexo = assertThrows(NexException.class, () -> Database.attach(ausente));
        assertTrue(anexo.getMessage().contains("does not exist"));
        assertFalse(Files.exists(ausente));
        Path vazio = dir.resolve("vazio.db");
        Files.write(vazio, new byte[0]);
        NexException vazioErro = assertThrows(NexException.class, () -> Database.open(vazio, "dba", "dba"));
        assertTrue(vazioErro.getMessage().contains("empty"));
        assertEquals(0, Files.size(vazio));
    }

    @Test
    void esquemas() {
        try (Database db = open()) {
            assertEquals(List.of("public"), db.schemas());
            assertEquals("public", db.session().schema());
            db.execute("CREATE SCHEMA vendas");
            db.execute("CREATE SCHEMA IF NOT EXISTS vendas");
            assertThrows(NexException.class, () -> db.execute("CREATE SCHEMA vendas"));
            db.execute("CREATE TABLE cliente (id INTEGER PRIMARY KEY)");
            db.execute("CREATE TABLE vendas.cliente (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            db.execute("INSERT INTO cliente (id) VALUES (1)");
            db.execute("INSERT INTO vendas.cliente (id, nome) VALUES (2, 'Ana')");
            db.execute("CREATE VIEW vendas.ativo AS SELECT nome FROM vendas.cliente");
            assertEquals(1L, db.query("SELECT id FROM cliente").rows().get(0).getLong("id"));
            assertEquals("Ana", db.query("SELECT nome FROM vendas.ativo").rows().get(0).getString("nome"));
            assertEquals("Ana", db.query("SELECT vendas.cliente.nome FROM vendas.cliente").rows().get(0).getString(0));
            db.execute("SET SCHEMA vendas");
            assertEquals("vendas", db.session().schema());
            assertEquals("Ana", db.query("SELECT nome FROM cliente").rows().get(0).getString("nome"));
            assertEquals("Ana", db.query("SELECT nome FROM ativo").rows().get(0).getString("nome"));
            assertEquals(1L, db.query("SELECT id FROM public.cliente").rows().get(0).getLong("id"));
            assertEquals("vendas", db.query("SELECT NEX$SCHEMA_NAME FROM NEX$SCHEMAS WHERE NEX$SCHEMA_NAME = 'vendas'").rows().get(0).getString(0));
            assertThrows(NexException.class, () -> db.execute("DROP SCHEMA vendas"));
            assertThrows(NexException.class, () -> db.execute("DROP SCHEMA public"));
            db.execute("SET SCHEMA public");
            assertThrows(NexException.class, () -> db.execute("DROP SCHEMA vendas"));
            db.execute("DROP SCHEMA vendas CASCADE");
            assertEquals(1L, db.query("SELECT id FROM cliente").rows().get(0).getLong("id"));
            assertThrows(NexException.class, () -> db.query("SELECT id FROM vendas.cliente"));
        }
        try (Database db = open()) {
            assertEquals(1L, db.query("SELECT id FROM public.cliente").rows().get(0).getLong("id"));
            assertThrows(NexException.class, () -> db.query("SELECT nome FROM vendas.ativo"));
            assertEquals(List.of("public"), db.schemas());
        }
    }

    @Test
    void procedureNoEsquema() {
        try (Database db = open()) {
            db.execute("CREATE SCHEMA vendas");
            db.execute("CREATE TABLE vendas.item (id INTEGER PRIMARY KEY)");
            db.execute("INSERT INTO vendas.item (id) VALUES (7)");
            db.execute("""
                    CREATE PROCEDURE eco
                    RETURNS (n INTEGER)
                    AS
                    BEGIN
                      n = 1;
                      SUSPEND;
                    END
                    """);
            db.execute("""
                    CREATE PROCEDURE vendas.eco
                    RETURNS (n INTEGER)
                    AS
                    BEGIN
                      n = 2;
                      SUSPEND;
                    END
                    """);
            db.execute("""
                    CREATE PROCEDURE vendas.ler
                    RETURNS (id INTEGER)
                    AS
                    BEGIN
                      SELECT id INTO id FROM item;
                      SUSPEND;
                    END
                    """);
            assertEquals(1L, db.query("SELECT n FROM eco()").rows().get(0).getLong("n"));
            assertEquals(2L, db.query("SELECT n FROM vendas.eco()").rows().get(0).getLong("n"));
            assertEquals(7L, db.query("SELECT id FROM vendas.ler()").rows().get(0).getLong("id"));
            assertEquals(2L, db.query("EXECUTE PROCEDURE vendas.eco").rows().get(0).getLong("n"));
            db.execute("SET SCHEMA vendas");
            assertEquals(2L, db.query("SELECT n FROM eco()").rows().get(0).getLong("n"));
            assertEquals(1L, db.query("SELECT n FROM public.eco()").rows().get(0).getLong("n"));
            assertThrows(NexException.class, () -> db.execute("CREATE PROCEDURE eco RETURNS (n INTEGER) AS BEGIN n = 3; SUSPEND; END"));
            db.execute("SET SCHEMA public");
            assertThrows(NexException.class, () -> db.execute("DROP SCHEMA vendas"));
            db.execute("CREATE SCHEMA outro");
            db.execute("CREATE PROCEDURE outro.nada AS BEGIN EXIT; END");
            assertThrows(NexException.class, () -> db.execute("DROP SCHEMA outro"));
            db.execute("DROP SCHEMA outro CASCADE");
            db.execute("DROP SCHEMA vendas CASCADE");
            assertThrows(NexException.class, () -> db.query("SELECT n FROM vendas.eco()"));
            assertEquals(1L, db.query("SELECT n FROM eco()").rows().get(0).getLong("n"));
        }
    }

    @Test
    void sequencialNoEsquema() {
        try (Database db = open()) {
            db.execute("CREATE SCHEMA vendas");
            db.execute("CREATE SEQUENCE seq_item START WITH 1");
            db.execute("CREATE SEQUENCE vendas.seq_item START WITH 10 INCREMENT BY 2");
            db.execute("""
                    CREATE PROCEDURE vendas.prox
                    RETURNS (n BIGINT)
                    AS
                    BEGIN
                      n = NEXT VALUE FOR seq_item;
                      SUSPEND;
                    END
                    """);
            assertEquals(1L, db.query("SELECT NEXT VALUE FOR seq_item AS n FROM NEX$DATABASE").rows().get(0).getLong("n"));
            assertEquals(10L, db.query("SELECT NEXT VALUE FOR vendas.seq_item AS n FROM NEX$DATABASE").rows().get(0).getLong("n"));
            assertEquals(2L, db.query("SELECT GEN_ID(seq_item, 1) AS n FROM NEX$DATABASE").rows().get(0).getLong("n"));
            assertEquals(11L, db.query("SELECT GEN_ID(vendas.seq_item, 1) AS n FROM NEX$DATABASE").rows().get(0).getLong("n"));
            assertEquals(13L, db.query("SELECT n FROM vendas.prox()").rows().get(0).getLong("n"));
            db.execute("SET SCHEMA vendas");
            assertEquals(15L, db.query("SELECT NEXT VALUE FOR seq_item AS n FROM NEX$DATABASE").rows().get(0).getLong("n"));
            assertEquals(3L, db.query("SELECT GEN_ID(public.seq_item, 1) AS n FROM NEX$DATABASE").rows().get(0).getLong("n"));
            assertThrows(NexException.class, () -> db.execute("CREATE SEQUENCE seq_item"));
            db.execute("SET SCHEMA public");
            assertThrows(NexException.class, () -> db.execute("DROP SCHEMA vendas"));
            db.execute("DROP SEQUENCE vendas.seq_item");
            db.execute("DROP PROCEDURE vendas.prox");
            db.execute("CREATE GENERATOR vendas.gen_item");
            assertThrows(NexException.class, () -> db.execute("DROP SCHEMA vendas"));
            db.execute("DROP SCHEMA vendas CASCADE");
            assertThrows(NexException.class, () -> db.query("SELECT GEN_ID(vendas.gen_item, 1) AS n FROM NEX$DATABASE"));
            assertEquals(4L, db.query("SELECT NEXT VALUE FOR seq_item AS n FROM NEX$DATABASE").rows().get(0).getLong("n"));
        }
    }

    @Test
    void exclusaoApagaOConteudoDoArquivo() throws Exception {
        String segredo = "SIGILO-9f3c-VALOR-EXCLUSIVO";
        String mantido = "MANTIDO-9f3c-VISIVEL";
        String longo = "SIGILO-BLOB-9f3c-" + "0123456789abcdef".repeat(20);
        try (Database db = open()) {
            db.execute("CREATE TABLE item (id INTEGER PRIMARY KEY, nome VARCHAR(80), nota VARCHAR(400))");
            db.execute("INSERT INTO item (id, nome, nota) VALUES (1, ?, ?)", segredo, longo);
            db.execute("INSERT INTO item (id, nome) VALUES (2, ?)", mantido);
        }
        byte[] antes = Files.readAllBytes(arquivo());
        assertTrue(contains(antes, segredo));
        assertTrue(contains(antes, longo));
        assertTrue(contains(antes, mantido));

        try (Database db = open()) {
            db.execute("BEGIN");
            db.execute("DELETE FROM item WHERE id = 1");
            db.execute("ROLLBACK");
            assertEquals(segredo, db.query("SELECT nome FROM item WHERE id = 1").rows().get(0).getString("nome"));
        }
        byte[] depoisDoRollback = Files.readAllBytes(arquivo());
        assertTrue(contains(depoisDoRollback, segredo));
        assertTrue(contains(depoisDoRollback, longo));

        try (Database db = open()) {
            db.execute("BEGIN");
            db.execute("DELETE FROM item WHERE id = 1");
            db.execute("COMMIT");
            assertEquals(0, db.query("SELECT id FROM item WHERE id = 1").size());
            assertEquals(mantido, db.query("SELECT nome FROM item WHERE id = 2").rows().get(0).getString("nome"));
        }
        byte[] depoisDoDelete = Files.readAllBytes(arquivo());
        assertFalse(contains(depoisDoDelete, segredo));
        assertFalse(contains(depoisDoDelete, longo));
        assertTrue(contains(depoisDoDelete, mantido));

        try (Database db = open()) {
            assertEquals(mantido, db.query("SELECT nome FROM item WHERE id = 2").rows().get(0).getString("nome"));
            db.execute("UPDATE item SET nome = ? WHERE id = 2", "TROCA-9f3c-NOVO");
        }
        byte[] depoisDoUpdate = Files.readAllBytes(arquivo());
        assertFalse(contains(depoisDoUpdate, mantido));
        assertTrue(contains(depoisDoUpdate, "TROCA-9f3c-NOVO"));

        String esquema = "sigiloqx9f3c";
        String tabela = "tabelaqx9f3c";
        try (Database db = open()) {
            db.execute("CREATE SCHEMA " + esquema);
            db.execute("CREATE TABLE " + esquema + "." + tabela + " (id INTEGER PRIMARY KEY, nome VARCHAR(80))");
            db.execute("INSERT INTO " + esquema + "." + tabela + " (id, nome) VALUES (1, ?)", segredo);
            db.execute("DROP SCHEMA " + esquema + " CASCADE");
            assertEquals(List.of("public"), db.schemas());
        }
        byte[] depoisDoDrop = Files.readAllBytes(arquivo());
        assertFalse(contains(depoisDoDrop, segredo));
        assertFalse(contains(depoisDoDrop, esquema));
        assertFalse(contains(depoisDoDrop, tabela));
        assertTrue(contains(depoisDoDrop, "TROCA-9f3c-NOVO"));
    }

    @Test
    void logAusenteCriaUmNovoZerado() throws Exception {
        Path arquivo = dir.resolve("loja.db");
        Database.create(arquivo, DatabaseOptions.defaults());
        try (Database db = Database.open(arquivo, "dba", "dba")) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            db.execute("INSERT INTO t VALUES (1, 'Ana')");
        }
        Path log = dir.resolve("loja.log");
        long cheio = Files.size(log);
        assertTrue(cheio > Format.LOG_HEADER_SIZE);
        Files.delete(log);
        try (Database db = Database.open(arquivo, "dba", "dba")) {
            assertEquals("Ana", db.query("SELECT nome FROM t WHERE id = 1").rows().get(0).getString("nome"));
        }
        long novo = Files.size(log);
        assertTrue(novo >= Format.LOG_HEADER_SIZE && novo < cheio);
        long marca = checkpoint(arquivo);
        assertTrue(marca >= Format.LOG_HEADER_SIZE && marca <= novo);
        try (Database db = Database.open(arquivo, "dba", "dba")) {
            db.execute("INSERT INTO t VALUES (2, 'Bia')");
        }
        assertTrue(Files.size(log) > novo);
        try (Database db = Database.open(arquivo, "dba", "dba")) {
            assertEquals("Bia", db.query("SELECT nome FROM t WHERE id = 2").rows().get(0).getString("nome"));
        }

        Files.write(log, new byte[0]);
        try (Database db = Database.open(arquivo, "dba", "dba")) {
            assertEquals(2L, db.query("SELECT COUNT(*) FROM t").rows().get(0).getLong(0));
        }
        assertTrue(Files.size(log) >= Format.LOG_HEADER_SIZE);

        Database.create(dir.resolve("outro.db"), DatabaseOptions.defaults());
        Files.copy(dir.resolve("outro.log"), log, StandardCopyOption.REPLACE_EXISTING);
        NexException alheio = assertThrows(NexException.class, () -> Database.open(arquivo, "dba", "dba"));
        assertTrue(alheio.getMessage().contains("does not belong"));
        NexException extensao = assertThrows(NexException.class, () -> Database.open(dir.resolve("loja.xdb"), "dba", "dba"));
        assertTrue(extensao.getMessage().contains(".db"));
    }

    @Test
    void logAntigoNaoAbreDepoisDeOutraGeracao() throws Exception {
        Path arquivo = dir.resolve("loja.db");
        Database.create(arquivo, DatabaseOptions.defaults());
        try (Database db = Database.open(arquivo, "dba", "dba")) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            db.execute("INSERT INTO t VALUES (1, 'Ana')");
        }
        Path log = dir.resolve("loja.log");
        long geracao = epoch(arquivo);
        assertEquals(geracao, epochLog(log));
        assertEquals(Files.size(log), checkpoint(arquivo));
        Path guardado = dir.resolve("loja.log.antigo");
        Files.move(log, guardado);
        try (Database db = Database.open(arquivo, "dba", "dba")) {
            assertEquals("Ana", db.query("SELECT nome FROM t WHERE id = 1").rows().get(0).getString("nome"));
            db.execute("INSERT INTO t VALUES (2, 'Bia')");
        }
        assertEquals(geracao + 1, epoch(arquivo));
        assertEquals(geracao + 1, epochLog(log));
        assertEquals(Files.size(log), checkpoint(arquivo));
        Files.delete(log);
        Files.move(guardado, log);
        long bytes = Files.size(log);
        NexException perdido = assertThrows(NexException.class, () -> Database.open(arquivo, "dba", "dba"));
        assertTrue(perdido.getMessage().contains("generation"));
        assertEquals(bytes, Files.size(log));
        assertEquals(geracao, epochLog(log));
        Files.delete(log);
        try (Database db = Database.open(arquivo, "dba", "dba")) {
            assertEquals("Bia", db.query("SELECT nome FROM t WHERE id = 2").rows().get(0).getString("nome"));
            assertEquals("Ana", db.query("SELECT nome FROM t WHERE id = 1").rows().get(0).getString("nome"));
        }
        assertEquals(geracao + 2, epoch(arquivo));
        assertEquals(Files.size(dir.resolve("loja.log")), checkpoint(arquivo));
    }

    @Test
    void recuperaCommitInterrompidoAntesDoCheckpoint() throws Exception {
        try (Database db = open()) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
        }
        Database db = open();
        TransactionLog.crashAfterRedo = true;
        try {
            NexException queda = assertThrows(NexException.class,
                    () -> db.execute("INSERT INTO t (id, nome) VALUES (1, 'Ana')"));
            assertTrue(queda.getMessage().contains("checkpoint"));
        } finally {
            TransactionLog.crashAfterRedo = false;
            db.close();
        }
        try (Database deNovo = open()) {
            assertEquals("Ana", deNovo.query("SELECT nome FROM t WHERE id = 1").rows().get(0).getString("nome"));
            assertEquals(1, deNovo.query("SELECT NEX$FORMAT FROM NEX$DATABASE").rows().get(0).getInt("NEX$FORMAT"));
        }
        assertTrue(Files.size(dir.resolve("banco.log")) > Format.LOG_HEADER_SIZE);
    }

    @Test
    void backupGuardaOLogEApplyAvancaOBanco() throws Exception {
        Path arquivo = dir.resolve("loja.db");
        Database.create(arquivo, DatabaseOptions.defaults());
        try (Database db = Database.open(arquivo, "dba", "dba")) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            db.execute("INSERT INTO t VALUES (1, 'antes')");
        }
        long logCheio = Files.size(dir.resolve("loja.log"));
        assertTrue(logCheio > Format.LOG_HEADER_SIZE);

        Path destino = dir.resolve("copia");
        Database.backup(arquivo, destino);
        assertEquals(Format.LOG_HEADER_SIZE, Files.size(dir.resolve("loja.log")));
        assertEquals(logCheio, Files.size(destino.resolve("loja.log")));

        try (Database db = Database.open(arquivo, "dba", "dba")) {
            db.execute("INSERT INTO t VALUES (2, 'depois')");
        }
        assertTrue(Files.size(dir.resolve("loja.log")) > Format.LOG_HEADER_SIZE);
        assertEquals(logCheio, Files.size(destino.resolve("loja.log")));
        byte[] log = Files.readAllBytes(dir.resolve("loja.log"));
        assertFalse(contains(log, "INSERT"));
        assertFalse(contains(log, "depois"));
        assertFalse(new String(Files.readAllBytes(destino.resolve("loja.db")), StandardCharsets.UTF_8).contains("depois"));

        NexException anterior = assertThrows(NexException.class,
                () -> Database.applyLog(arquivo, destino.resolve("loja.log")));
        assertTrue(anterior.getMessage().contains("previous"));

        try (Database db = Database.open(destino.resolve("loja.db"), "dba", "dba")) {
            assertEquals("antes", db.query("SELECT nome FROM t WHERE id = 1").rows().get(0).getString("nome"));
        }
        Database.applyLog(destino.resolve("loja.db"), dir.resolve("loja.log"));
        try (Database db = Database.open(destino.resolve("loja.db"), "dba", "dba")) {
            assertEquals("depois", db.query("SELECT nome FROM t WHERE id = 2").rows().get(0).getString("nome"));
            assertEquals("antes", db.query("SELECT nome FROM t WHERE id = 1").rows().get(0).getString("nome"));
            assertEquals(1, db.query("SELECT NEX$FORMAT FROM NEX$DATABASE").rows().get(0).getInt("NEX$FORMAT"));
        }

        Database.create(dir.resolve("outro.db"), DatabaseOptions.defaults());
        NexException alheio = assertThrows(NexException.class,
                () -> Database.applyLog(destino.resolve("loja.db"), dir.resolve("outro.log")));
        assertTrue(alheio.getMessage().contains("does not belong"));
    }

    @Test
    void backupDeModificacaoCopiaSoOLog() throws Exception {
        Path arquivo = dir.resolve("loja.db");
        Database.create(arquivo, DatabaseOptions.defaults());
        try (Database db = Database.open(arquivo, "dba", "dba")) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            db.execute("INSERT INTO t VALUES (1, 'antes')");
        }
        Path completo = dir.resolve("completo");
        Database.backup(arquivo, completo);
        assertEquals(Format.LOG_HEADER_SIZE, Files.size(dir.resolve("loja.log")));
        assertTrue(Files.exists(completo.resolve("loja.db")));

        try (Database db = Database.open(arquivo, "dba", "dba")) {
            db.execute("INSERT INTO t VALUES (2, 'depois')");
        }
        long vivo = Files.size(dir.resolve("loja.log"));
        Path mods = dir.resolve("mods");
        Database.backupLog(arquivo, mods);
        assertEquals(vivo, Files.size(dir.resolve("loja.log")));
        assertEquals(vivo, Files.size(mods.resolve("loja.log")));
        assertFalse(Files.exists(mods.resolve("loja.db")));

        try (Database db = Database.open(arquivo, "dba", "dba")) {
            db.execute("INSERT INTO t VALUES (3, 'mais')");
        }
        assertTrue(Files.size(dir.resolve("loja.log")) > vivo);
        assertEquals(vivo, Files.size(mods.resolve("loja.log")));

        Database.applyLog(completo.resolve("loja.db"), mods.resolve("loja.log"));
        try (Database db = Database.open(completo.resolve("loja.db"), "dba", "dba")) {
            assertEquals("depois", db.query("SELECT nome FROM t WHERE id = 2").rows().get(0).getString("nome"));
            assertEquals(0, db.query("SELECT nome FROM t WHERE id = 3").rows().size());
        }
        Database.applyLog(completo.resolve("loja.db"), dir.resolve("loja.log"));
        try (Database db = Database.open(completo.resolve("loja.db"), "dba", "dba")) {
            assertEquals("mais", db.query("SELECT nome FROM t WHERE id = 3").rows().get(0).getString("nome"));
            assertEquals(1, db.query("SELECT NEX$FORMAT FROM NEX$DATABASE").rows().get(0).getInt("NEX$FORMAT"));
        }
    }

    private static long checkpoint(Path database) throws Exception {
        byte[] header = Files.readAllBytes(database);
        return ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getLong(Format.CHECKPOINT_LSN_OFFSET);
    }

    private static long epoch(Path database) throws Exception {
        byte[] header = Files.readAllBytes(database);
        return ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getLong(Format.LOG_EPOCH_OFFSET);
    }

    private static long epochLog(Path log) throws Exception {
        byte[] header = Files.readAllBytes(log);
        return ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getLong(Format.LOG_EPOCH_AT);
    }

    private static boolean contains(byte[] file, String text) {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        if (needle.length == 0 || needle.length > file.length) {
            return false;
        }
        for (int i = 0; i <= file.length - needle.length; i++) {
            boolean found = true;
            for (int j = 0; j < needle.length; j++) {
                if (file[i + j] != needle[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return true;
            }
        }
        return false;
    }

    private static TableDescription table(Database db, String name) {
        return db.describeTables().stream().filter(table -> table.name().equals(name)).findFirst().orElseThrow();
    }
}
