package io.github.gabrielmmoraes1999.nexora;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * Nexora. O banco é um arquivo {@code .db}: tabelas, BLOB,
 * configuração, usuários e conexões ficam nele. O {@code .log} ao lado
 * guarda as transações confirmadas desde a criação ou o último backup completo.
 * Se esse arquivo não existe, ou está vazio, a abertura cria um log só com o cabeçalho
 * e avança a geração: o arquivo antigo deixa de abrir o banco. A abertura e o fechamento gravam o checkpoint.
 *
 * <p>A abertura exige usuário e senha e um arquivo que já existe.
 * {@link #create(Path, DatabaseOptions)} escolhe a página, a senha e o nome.
 * A senha é gravada com bcrypt.
 *
 * <pre>{@code
 * try (Database db = Database.open(Path.of("loja.db"), "dba", "dba")) {
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
 * é {@code jdbc:nexora:embedded:<arquivo.db>} ou {@code jdbc:nexora://host:porta/nome}.
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

    /**
     * Backup completo: copia o {@code .db} e o {@code .log} para a pasta
     * e faz o log vivo passar a registrar só o que mudar depois.
     */
    public static void backup(Path database, Path directory) {
        if (database == null) {
            throw new NexException("o arquivo do banco é obrigatório");
        }
        if (directory == null) {
            throw new NexException("a pasta do backup é obrigatória");
        }
        try (DatabaseFile file = DatabaseFile.open(database)) {
            file.backupTo(directory);
        }
    }

    /**
     * Backup de modificação: copia o {@code .log} para a pasta.
     * O log vivo permanece, com as transações desde a criação ou o último backup completo.
     */
    public static void backupLog(Path database, Path directory) {
        if (database == null) {
            throw new NexException("o arquivo do banco é obrigatório");
        }
        if (directory == null) {
            throw new NexException("a pasta do backup é obrigatória");
        }
        try (DatabaseFile file = DatabaseFile.open(database)) {
            file.backupLogTo(directory);
        }
    }

    /**
     * Aplica um log sobre o {@code .db}. A mesma geração avança o banco a
     * partir do checkpoint. A geração seguinte traz o que mudou depois do backup.
     */
    public static void applyLog(Path database, Path log) {
        if (database == null) {
            throw new NexException("o arquivo do banco é obrigatório");
        }
        if (log == null) {
            throw new NexException("o log de transação é obrigatório");
        }
        try (Engine engine = Engine.open(database)) {
            engine.applyExternalLog(log);
        }
    }

    /**
     * Lê as transações comprimidas do {@code .log} e grava o SQL em {@code destination}.
     * {@code source} pode ser o {@code .log} ou o {@code .db} ao lado dele.
     * Os parâmetros entram no texto no lugar de cada {@code ?}.
     */
    public static void exportSql(Path source, Path destination) {
        exportSql(source, destination, null);
    }

    /**
     * Lê as transações comprimidas do {@code .log} e grava o SQL em {@code destination}.
     * {@code progress} recebe 0 no início e 100 quando o arquivo está gravado.
     */
    public static void exportSql(Path source, Path destination, IntConsumer progress) {
        if (source == null) {
            throw new NexException("o log de transação é obrigatório");
        }
        if (destination == null) {
            throw new NexException("o arquivo .sql é obrigatório");
        }
        Path log = logOf(source);
        Path target = destination.toAbsolutePath().normalize();
        if (target.equals(log)) {
            throw new NexException("o destino do SQL é outro arquivo");
        }
        if (Files.exists(target) && Files.isDirectory(target)) {
            throw new NexException("o destino do SQL é um arquivo");
        }
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String script;
            try (TransactionLog transactionLog = TransactionLog.inspect(log)) {
                long total = Math.max(1L, transactionLog.size());
                report(progress, 0);
                List<TransactionLog.Batch> batches = transactionLog.readTransactions(Format.LOG_HEADER_SIZE, at ->
                        report(progress, (int) Math.min(99L, at * 99L / total)));
                script = scriptOf(batches);
            }
            Files.writeString(target, script, StandardCharsets.UTF_8);
            report(progress, 100);
        } catch (IOException exception) {
            throw new NexException("não foi possível gravar o SQL: " + exception.getMessage(), exception);
        }
    }

    private static void report(IntConsumer progress, int percent) {
        if (progress != null) {
            progress.accept(percent);
        }
    }

    private static Path logOf(Path source) {
        Path path = source.toAbsolutePath().normalize();
        Path name = path.getFileName();
        String text = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        if (text.endsWith(".db")) {
            return TransactionLog.beside(path);
        }
        if (text.endsWith(".log")) {
            return path;
        }
        throw new NexException("informe um arquivo .log ou o .db ao lado dele");
    }

    private static String scriptOf(List<TransactionLog.Batch> batches) {
        StringBuilder script = new StringBuilder();
        String schema = null;
        for (TransactionLog.Batch batch : batches) {
            if (script.length() > 0) {
                script.append('\n');
            }
            for (TransactionLog.Applied statement : batch.statements) {
                if (statement.sql == null || statement.sql.isBlank()) {
                    continue;
                }
                if (schema == null || !schema.equalsIgnoreCase(statement.schema)) {
                    schema = statement.schema;
                    script.append("SET SCHEMA ").append(schemaName(schema)).append(";\n");
                }
                script.append(bind(statement.sql, statement.params)).append('\n');
            }
        }
        if (script.length() == 0 || script.charAt(script.length() - 1) != '\n') {
            script.append('\n');
        }
        return script.toString();
    }

    private static String bind(String sql, Object[] params) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        boolean quote = false;
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            if (current == '\'') {
                out.append(current);
                if (quote && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    out.append('\'');
                    i++;
                    continue;
                }
                quote = !quote;
                continue;
            }
            if (current == '?' && !quote) {
                if (params != null && index < params.length) {
                    out.append(literal(params[index]));
                } else {
                    out.append('?');
                }
                index++;
                continue;
            }
            out.append(current);
        }
        String text = out.toString().strip();
        if (text.endsWith(";")) {
            return text;
        }
        return text + ";";
    }

    private static String schemaName(String schema) {
        if (schema != null && schema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return schema;
        }
        return "\"" + (schema == null ? "public" : schema.replace("\"", "\"\"")) + "\"";
    }

    private static String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Boolean bool) {
            return bool ? "TRUE" : "FALSE";
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return value.toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Float || value instanceof Double) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) {
            StringBuilder hex = new StringBuilder("X'");
            for (byte piece : bytes) {
                hex.append(String.format("%02X", piece & 0xFF));
            }
            return hex.append('\'').toString();
        }
        if (value instanceof LocalDate date) {
            return "'" + date + "'";
        }
        if (value instanceof LocalTime time) {
            return "'" + time + "'";
        }
        if (value instanceof LocalDateTime time) {
            return "'" + time.toString().replace('T', ' ') + "'";
        }
        if (value instanceof java.sql.Date date) {
            return "'" + date.toLocalDate() + "'";
        }
        if (value instanceof java.sql.Time time) {
            return "'" + time.toLocalTime() + "'";
        }
        if (value instanceof java.sql.Timestamp time) {
            return "'" + time.toLocalDateTime().toString().replace('T', ' ') + "'";
        }
        return "'" + value.toString().replace("'", "''") + "'";
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
     * Abre o arquivo sem autenticar. O motor de rede segura o {@code .db} assim
     * e chama {@link #login} em cada cliente. O arquivo precisa já existir.
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
        return login(user, password, null, null);
    }

    /**
     * Entra com um papel já concedido. O DBA pode usar qualquer papel existente.
     * A origem gravada é a deste processo.
     */
    public Session login(String user, String password, String role) {
        return login(user, password, role, null);
    }

    /**
     * Entra e grava a origem remota em {@code NEX$CONNECTIONS}.
     * {@code remote} nulo usa o processo local.
     */
    public Session login(String user, String password, String role, RemoteClient remote) {
        synchronized (engine) {
            return engine.login(user, password, role, remote);
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

    /**
     * A sessão tem {@code BEGIN} sem {@code COMMIT} nem {@code ROLLBACK}.
     * Outra sessão pode ter a sua própria transação ao mesmo tempo.
     */
    public boolean transactionActive(Session session) {
        synchronized (engine) {
            return engine.transactionActive(session);
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
