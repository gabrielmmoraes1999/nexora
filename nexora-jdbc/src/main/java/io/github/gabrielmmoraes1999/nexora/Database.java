package io.github.gabrielmmoraes1999.nexora;

import java.nio.file.Path;
import java.util.List;

/**
 * Nexora. O banco é um arquivo {@code .xdb}: tabelas, BLOB,
 * configuração, usuários e conexões ficam nesse mesmo arquivo.
 *
 * <p>A abertura exige usuário e senha. Um arquivo novo, aberto pelo JDBC,
 * nasce com página de 8192 bytes e DBA {@code dba} / {@code dba}.
 * {@link #create(Path, DatabaseOptions)} e o comando {@code create}
 * escolhem a página, a senha e o nome antes da primeira conexão.
 * A senha é gravada com bcrypt.
 *
 * <pre>{@code
 * try (Database db = Database.open(Path.of("loja.xdb"), "dba", "dba")) {
 *     db.execute("""
 *             CREATE TABLE cliente (
 *                 id INTEGER PRIMARY KEY AUTOINCREMENT,
 *                 nome VARCHAR(120) NOT NULL,
 *                 foto BLOB
 *             )
 *             """);
 *     db.execute("INSERT INTO cliente (nome, foto) VALUES (?, ?)", "Ana", foto);
 * }
 * }</pre>
 *
 * <p>No DBeaver, o driver é {@link io.github.gabrielmmoraes1999.nexora.jdbc.NexDriver} e a URL
 * é {@code jdbc:nexora:embedded:<arquivo.xdb>} ou {@code jdbc:nexora://host:porta/nome}.
 */
public final class Database implements AutoCloseable {

    private final Engine engine;
    private Session owner;

    private Database(Engine engine, Session owner) {
        this.engine = engine;
        this.owner = owner;
    }

    /**
     * Cria um arquivo novo. A página é 4096, 8192, 16384 ou 32768 e não muda depois.
     * Um arquivo que já tem conteúdo não é reaproveitado.
     */
    public static void create(Path file, DatabaseOptions options) {
        if (file == null) {
            throw new NexException("o arquivo do banco é obrigatório");
        }
        if (options == null) {
            throw new NexException("as opções do banco são obrigatórias");
        }
        Engine.create(file, options);
    }

    public static Database open(Path file, String user, String password) {
        return open(file, user, password, null);
    }

    /**
     * Abre o arquivo com um papel. {@code null}, vazio ou {@code NONE} entram sem papel.
     * O usuário precisa ter recebido o papel, salvo o DBA.
     */
    public static Database open(Path file, String user, String password, String role) {
        if (user == null || user.isBlank() || password == null || password.isEmpty()) {
            throw new NexException("usuário e senha são obrigatórios");
        }
        if (file == null) {
            throw new NexException("o arquivo do banco é obrigatório");
        }
        Engine engine = Engine.open(file);
        try {
            return new Database(engine, engine.login(user, password, role));
        } catch (RuntimeException exception) {
            engine.close();
            throw exception;
        }
    }

    public static Database open(String file, String user, String password) {
        if (file == null || file.isBlank()) {
            throw new NexException("o arquivo do banco é obrigatório");
        }
        return open(Path.of(file), user, password);
    }

    /**
     * Abre o arquivo sem autenticar. O motor de rede segura o {@code .xdb} assim
     * e chama {@link #login} em cada cliente. Um arquivo que ainda não existe
     * nasce com página de 8192 bytes e DBA {@code dba} / {@code dba}.
     */
    public static Database attach(Path file) {
        if (file == null) {
            throw new NexException("o arquivo do banco é obrigatório");
        }
        return new Database(Engine.open(file), null);
    }

    /**
     * Executa um ou mais comandos separados por {@code ';'}.
     * O retorno é o resultado do último comando.
     * Placeholders {@code ?} são posicionais e valem para o script inteiro.
     */
    public SqlResult execute(String sql, Object... params) {
        synchronized (engine) {
            if (owner == null || owner.closed()) {
                throw new NexException("sessão encerrada");
            }
            return engine.execute(owner, sql, params);
        }
    }

    public SqlResult execute(Session session, String sql, Object... params) {
        synchronized (engine) {
            return engine.execute(session, sql, params);
        }
    }

    public Session login(String user, String password) {
        return login(user, password, null);
    }

    /**
     * Entra com um papel já concedido. O DBA pode usar qualquer papel existente.
     */
    public Session login(String user, String password, String role) {
        synchronized (engine) {
            return engine.login(user, password, role);
        }
    }

    public void logout(Session session) {
        synchronized (engine) {
            engine.detach(session);
            if (owner == session) {
                owner = null;
            }
        }
    }

    public Session session() {
        return owner;
    }

    public int executeUpdate(String sql, Object... params) {
        SqlResult result = execute(sql, params);
        if (result.isQuery()) {
            throw new NexException("o comando retornou linhas; use query ou execute");
        }
        return result.affectedRows();
    }

    public SqlResult query(String sql, Object... params) {
        SqlResult result = execute(sql, params);
        if (!result.isQuery()) {
            throw new NexException("o comando não é uma consulta");
        }
        return result;
    }

    public List<String> tables() {
        synchronized (engine) {
            return engine.tableNames();
        }
    }

    /**
     * Esquemas do arquivo, na ordem do nome. Todo banco tem {@code public}.
     */
    public List<String> schemas() {
        synchronized (engine) {
            return engine.schemaNames();
        }
    }

    /**
     * Define o esquema da sessão. Nome sem aspas não distingue maiúsculas.
     * O padrão, ao conectar, é {@code public}.
     */
    public void setSchema(Session session, String schema) {
        synchronized (engine) {
            engine.useSchema(session, schema);
        }
    }

    /**
     * Descreve as tabelas do usuário, na ordem do nome, com colunas e índices.
     * As tabelas {@code NEX$} ficam de fora.
     */
    public List<TableDescription> describeTables() {
        synchronized (engine) {
            return engine.describeTables();
        }
    }

    public List<TableDescription> describeAllTables() {
        synchronized (engine) {
            return engine.describeAllTables();
        }
    }

    /**
     * Descreve as views, na ordem do nome. Cada uma é somente leitura e não tem índice.
     */
    public List<TableDescription> describeViews() {
        synchronized (engine) {
            return engine.describeViews();
        }
    }

    public Path file() {
        return engine.file;
    }

    /**
     * Tamanho da página, em bytes. É o valor gravado na criação do arquivo.
     */
    public int pageSize() {
        return engine.pageSize();
    }

    public boolean transactionActive() {
        synchronized (engine) {
            return engine.transactionActive();
        }
    }

    @Override
    public void close() {
        synchronized (engine) {
            try {
                if (owner != null && !owner.closed()) {
                    engine.discardTransaction();
                    engine.detach(owner);
                }
            } finally {
                owner = null;
                engine.close();
            }
        }
    }
}
