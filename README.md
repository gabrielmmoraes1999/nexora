# Nexora — JDBC

Driver JDBC do Nexora. O banco é um arquivo `.xdb`: as tabelas, os `BLOB`, a configuração, os usuários e o registro de conexões ficam nesse mesmo arquivo. A aplicação fala com ele por `java.sql`, com `DriverManager`, pool ou DBeaver.

Java 21. O driver declara JDBC 4.2 e não é um driver certificado (`jdbcCompliant()` devolve `false`).

São dois projetos Maven, independentes. `nexora-server` tem o engine e o motor de rede. `nexora-jdbc` tem o próprio engine e o driver, com conexão embutida e conexão ao motor. Nenhum depende do outro.

## Instalação

O driver e o motor são projetos Maven próprios.

```bash
mvn -f nexora-jdbc/pom.xml install
mvn -f nexora-server/pom.xml install
```

Dependência da aplicação, só o driver:

```xml
<dependency>
    <groupId>io.github.gabrielmmoraes1999</groupId>
    <artifactId>nexora-jdbc</artifactId>
    <version>1.0</version>
</dependency>
```

O serviço `META-INF/services/java.sql.Driver` registra o driver. `DriverManager.getConnection` o encontra sem `Class.forName`.

| | |
|---|---|
| Classe | `io.github.gabrielmmoraes1999.nexora.jdbc.NexDriver` |
| URL embutida | `jdbc:nexora:embedded:<arquivo.xdb>` |
| URL de rede | `jdbc:nexora://<host>:<porta>/<nome>` |
| Usuário | `dba` |
| Senha | `dba` |
| Produto | Nexora 1.0 |

```java
String url = "jdbc:nexora:embedded:C:/dados/loja.xdb";
try (Connection connection = DriverManager.getConnection(url, "dba", "dba")) {
    // ...
}
```

`jdbc:nexora:C:/dados/loja.xdb` continua válido. `jdbc:nexora://C:/dados/loja.xdb` também: a barra antes da letra de unidade é ignorada. A URL sem arquivo (`jdbc:nexora:`) falha. Para outro processo, use a URL de rede descrita em [Rede](#rede). Usuário ou senha em branco falha com SQLSTATE `28000`. Senha errada falha com a mensagem de usuário ou senha inválidos.

O arquivo é criado se ainda não existir, com página de 8192 bytes e usuário `dba`. A página fica fixa. Para escolher outro tamanho, a senha do DBA, o nome ou o conjunto de caracteres, crie o arquivo antes pela linha de comando.

A senha não fica em texto puro. Cada gravação usa bcrypt (`$2a$`, custo 10). `Connection.getMetaData().getUserName()` devolve o usuário autenticado, em maiúsculas.

`getCatalog()` devolve `null`. `getSchema()` devolve o esquema da sessão, `public` ao conectar. `setSchema` troca esse esquema.

## Criar o banco

O comando `create` gera o `.xdb` e o usuário DBA. A página segue o Firebird: `4096`, `8192`, `16384` ou `32768`. O padrão é `8192`. Ela não muda depois da criação. A página 0 é o cabeçalho; o catálogo e as tabelas ocupam páginas inteiras.

```bash
java -jar nexora-jdbc/target/nexora-jdbc-1.0.jar create C:/dados/loja.xdb --page-size 16384 --user DBA --password segredo --name LOJA --charset UTF8
```

| Opção | Padrão |
|---|---|
| `--page-size` | `8192` |
| `--user` | `DBA` |
| `--password` | `dba` |
| `--name` | nome do arquivo, sem a extensão |
| `--description` | `Nexora` |
| `--charset` | `UTF8` |

Um arquivo que já tem conteúdo não é reescrito. Sem argumentos, ou com `--help`, o comando mostra o uso. Na API, o mesmo efeito é `Database.create(arquivo, options)`.

`NEX$PAGE_SIZE` guarda o tamanho da página. `NEX$FORMAT` é `1`. `Default.CharacterSet` em `NEX$CONFIG` guarda o conjunto informado na criação.

## Rede

O arquivo `.xdb` fica aberto num processo só. O projeto `nexora-server` é esse processo: lê um json com o nome e o caminho de cada banco, escuta numa porta e atende o JDBC. O driver, no projeto `nexora-jdbc`, fala com ele por `jdbc:nexora://host:porta/nome` e também abre o arquivo direto com `jdbc:nexora:embedded:`. A senha vai em claro no TCP, pensada para a rede local.

```json
{
  "host": "0.0.0.0",
  "port": 4580,
  "databases": [
    { "name": "loja", "path": "C:/dados/loja.xdb" }
  ]
}
```

`host` omitido escuta em todas as interfaces. A porta e ao menos um banco são obrigatórios. Um caminho relativo resolve a partir da pasta do json. O arquivo ausente nasce como no JDBC embutido, com página 8192 e DBA `dba` / `dba`.

```bash
java -jar nexora-server/target/nexora-server-1.0.jar serve motor.json
```

No cliente:

```java
String url = "jdbc:nexora://192.168.0.10:4580/loja";
try (Connection connection = DriverManager.getConnection(url, "dba", "dba")) {
    // ...
}
```

O nome depois da porta é o do json, sem distinguir maiúsculas. Várias conexões no motor seguem a regra de uma transação por arquivo. Enquanto o motor segura o `.xdb`, outro processo que tentar abrir o mesmo arquivo recebe banco já aberto. Ctrl+C solta os arquivos.

## DBeaver

1. Nova conexão, driver personalizado.
2. Classe: `io.github.gabrielmmoraes1999.nexora.jdbc.NexDriver`.
3. URL: `jdbc:nexora:C:/dados/loja.xdb`.
4. Usuário `dba`, senha `dba`.
5. Bibliotecas: o JAR do driver (`nexora-jdbc/target/nexora-jdbc-1.0.jar`). O engine vai dentro desse JAR. No DBeaver, troque o JAR antigo por este. O JAR do motor não entra no DBeaver.

## Exemplo

```java
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

Path arquivo = Path.of("dados", "loja.xdb");
Files.createDirectories(arquivo.getParent());

try (Connection connection = DriverManager.getConnection("jdbc:nexora:" + arquivo.toAbsolutePath(), "dba", "dba")) {
    try (Statement statement = connection.createStatement()) {
        statement.execute("""
                CREATE TABLE cliente (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    nome VARCHAR(120) NOT NULL,
                    email VARCHAR(120) UNIQUE,
                    nascimento DATE,
                    foto BLOB
                )
                """);
        statement.execute("CREATE INDEX idx_nome ON cliente (nome)");
    }

    byte[] foto = Files.readAllBytes(Path.of("ana.jpg"));
    try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO cliente (nome, email, nascimento, foto) VALUES (?, ?, ?, ?)")) {
        insert.setString(1, "Ana");
        insert.setString(2, "ana@exemplo.com");
        insert.setDate(3, Date.valueOf("1990-04-12"));
        insert.setBytes(4, foto);
        insert.executeUpdate();
    }

    try (PreparedStatement query = connection.prepareStatement(
            "SELECT id, nome, foto FROM cliente WHERE email = ?")) {
        query.setString(1, "ana@exemplo.com");
        try (ResultSet rows = query.executeQuery()) {
            if (rows.next()) {
                long id = rows.getLong("id");
                String nome = rows.getString("nome");
                byte[] bytes = rows.getBytes("foto");
            }
        }
    }
}
```

## SQL

Vários comandos podem ir no mesmo `execute`, separados por `;`. Um `SELECT` precisa ser o último. Os `?` são posicionais e valem para o script inteiro. Comentários são `--` até o fim da linha e `/* ... */`.

Identificadores sem aspas não distinguem maiúsculas. Com aspas, as maiúsculas são preservadas e um `"` interno se escreve `""`. Nome de tabela, coluna e índice tem no máximo 128 caracteres. O `$` entra no nome, como em `NEX$USERS`.

### Tipos

| SQL | JDBC `Types` | Literal / `PreparedStatement` |
|---|---|---|
| `SMALLINT` | `SMALLINT` | inteiro, `setShort` |
| `INTEGER` ou `INT` | `INTEGER` | inteiro, `setInt` |
| `BIGINT` | `BIGINT` | inteiro, `setLong` |
| `FLOAT` ou `REAL` | `REAL` | `setFloat` |
| `DOUBLE` ou `DOUBLE PRECISION` | `DOUBLE` | `setDouble` |
| `DECIMAL(p, s)` ou `NUMERIC(p, s)` | `DECIMAL` | `12.50`, `setBigDecimal` |
| `BOOLEAN` ou `BOOL` | `BOOLEAN` | `TRUE`, `FALSE`, `setBoolean` |
| `CHAR(n)` ou `CHARACTER(n)` | `CHAR` | `'texto'`, `setString` |
| `VARCHAR(n)` ou `CHARACTER VARYING(n)` | `VARCHAR` | `'texto'`, `setString` |
| `DATE` | `DATE` | `'2026-10-07'`, `setDate` |
| `TIME` | `TIME` | `'13:45'` ou `'13:45:01'`, `setTime` |
| `TIMESTAMP` | `TIMESTAMP` | `'2026-10-07 13:45:01'` ou `'2026-10-07T13:45:01'`, `setTimestamp` |
| `BLOB`, `BYTEA`, `BINARY` ou `VARBINARY` | `BLOB` | `X'0102'`, `X''`, `setBytes` |

`CHAR` e `VARCHAR` pedem tamanho de 1 a 8192. `DECIMAL` pede precisão de 1 a 38, e a escala não passa da precisão. Uma tabela tem no máximo 1024 colunas.

`NULL` é nulo. Aspas simples dentro de texto dobram: `'d''agua'`. `X'0102'` é binário; o número de dígitos hexadecimais precisa ser par.

```java
prepared.setNull(4, Types.BLOB);
prepared.setObject(1, "Ana");
```

O índice do parâmetro começa em 1.

### Tabelas

```sql
CREATE TABLE pedido (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    cliente_id INTEGER NOT NULL,
    total DECIMAL(12, 2) DEFAULT 0,
    aberto BOOLEAN DEFAULT TRUE,
    criado TIMESTAMP
);

CREATE TABLE item (
    pedido_id INTEGER NOT NULL,
    numero INTEGER NOT NULL,
    produto VARCHAR(80) NOT NULL,
    quantidade INTEGER DEFAULT 1,
    PRIMARY KEY (pedido_id, numero),
    UNIQUE (pedido_id, produto)
);

CREATE TABLE IF NOT EXISTS pedido (
    id INTEGER PRIMARY KEY
);

ALTER TABLE pedido ADD COLUMN obs VARCHAR(200) DEFAULT '';
ALTER TABLE pedido DROP COLUMN obs;

CREATE TABLE nota (
    id INTEGER NOT NULL,
    texto VARCHAR(80)
);

ALTER TABLE nota ADD PRIMARY KEY (id);
DROP TABLE IF EXISTS rascunho;
DROP TABLE pedido;
```

Uma tabela tem uma chave primária. Ela pode ser uma coluna (`id INTEGER PRIMARY KEY`) ou composta (`PRIMARY KEY (a, b)`), na criação ou depois com `ALTER TABLE ... ADD PRIMARY KEY`. `ADD CONSTRAINT nome PRIMARY KEY` também vale; o nome não é guardado. A tabela só aceita uma chave. As colunas da chave passam a ser `NOT NULL`. Se já houver nulo ou valor repetido nessas colunas, o comando falha e a tabela fica como estava. `AUTOINCREMENT` e `IDENTITY` valem em `SMALLINT`, `INTEGER` e `BIGINT`, e o primeiro valor é 1. `UNIQUE` pode ser da coluna ou da tabela. `DEFAULT` aceita um literal, `NULL`, `TRUE` ou `FALSE`. `NOT NULL` e `NULL` não se combinam na mesma coluna.

`getGeneratedKeys()` devolve um resultado vazio. O valor gerado se lê com `SELECT`.

### Esquemas

O esquema padrão é `public`, como no PostgreSQL. Um nome sem esquema usa o esquema da sessão. `esquema.tabela` aponta para outro. O mesmo nome de tabela pode existir em esquemas diferentes. `public` nasce com o arquivo e não pode ser removido.

```sql
CREATE SCHEMA vendas;
CREATE TABLE vendas.pedido (
    id INTEGER PRIMARY KEY
);
INSERT INTO vendas.pedido (id) VALUES (1);
SELECT id FROM vendas.pedido;
SELECT vendas.pedido.id FROM vendas.pedido;

SET SCHEMA vendas;
SELECT id FROM pedido;
SET SCHEMA public;

DROP SCHEMA vendas RESTRICT;
DROP SCHEMA IF EXISTS vendas CASCADE;
```

`CREATE SCHEMA IF NOT EXISTS` não falha se o esquema já existe. `DROP SCHEMA` sem `CASCADE` exige o esquema vazio. `CASCADE` remove as tabelas e as views desse esquema, e as views de fora que dependem delas. Não remove o esquema da sessão atual: faça `SET SCHEMA public` antes. `GRANT` e `REVOKE` aceitam `esquema.tabela`. Tabelas `NEX$` ficam em `public` e continuam visíveis sem qualificar, em qualquer esquema da sessão.

### Índices

```sql
CREATE INDEX idx_cliente ON pedido (cliente_id);
CREATE UNIQUE INDEX idx_email ON cliente (email);
CREATE INDEX IF NOT EXISTS idx_item ON item (pedido_id, numero);
DROP INDEX idx_cliente ON pedido;
DROP INDEX IF EXISTS idx_cliente ON pedido;
```

`CREATE UNIQUE INDEX` recusa duplicata, inclusive nas linhas que já existem. `BLOB` não entra em índice nem em chave. Os nomes `PRIMARY` e `UNIQUE_1`, `UNIQUE_2`, … estão reservados. Remover uma coluna remove os índices que a usam.

As entradas ficam em páginas de 4096 bytes na região da tabela. O arquivo cresce com as chaves. A igualdade lê só as páginas do caminho, da raiz até a folha, e a lista inteira de chaves não permanece na memória. Os registros da tabela continuam sendo carregados ao abrir.

O plano usa o índice quando o `WHERE` traz igualdades ligadas por `AND` que cobrem todas as colunas do índice, com literal ou `?`:

```sql
SELECT id FROM cliente WHERE email = ?;
SELECT * FROM item WHERE pedido_id = 10 AND numero = 2;
```

`LIKE`, `OR`, intervalo e expressão ficam de fora dessa busca e a tabela é percorrida.

`CREATE`, `DROP` e `ALTER` não rodam com uma transação aberta.

### Linhas

```sql
INSERT INTO cliente (nome, email) VALUES ('Ana', 'ana@exemplo.com');
INSERT INTO item (pedido_id, numero, produto, quantidade)
VALUES (1, 1, 'Caderno', 2), (1, 2, 'Lápis', 10);

UPDATE cliente SET nome = ? WHERE id = ?;
DELETE FROM item WHERE pedido_id = 1 AND numero = 2;
```

`INSERT ... SELECT` não existe. A lista de colunas pode ser omitida quando os valores seguem a ordem da tabela.

### Consultas

Uma consulta lê uma tabela, com alias, `DISTINCT`, `WHERE`, `ORDER BY`, `LIMIT` e `OFFSET`. O `FROM` pode ficar de fora quando a lista não usa coluna: o resultado tem uma linha, e `WHERE` falso devolve nenhuma. `SELECT *` continua pedindo tabela.

```sql
SELECT id, nome AS cliente
FROM cliente c
WHERE c.nome LIKE 'A%' AND nascimento >= '1990-01-01'
ORDER BY nome ASC, id DESC
LIMIT 20 OFFSET 40;

SELECT DISTINCT aberto FROM pedido;
SELECT COUNT(*) FROM cliente;
SELECT COALESCE(email, nome) FROM cliente WHERE id = 1;
SELECT UPPER(nome), LENGTH(foto) FROM cliente WHERE foto IS NOT NULL;
```

Predicados: `=`, `<>`, `!=`, `<`, `>`, `<=`, `>=`, `AND`, `OR`, `NOT`, `IS NULL`, `IS NOT NULL`, `IN (...)`, `BETWEEN ... AND ...`, `LIKE`, `NOT LIKE`, `NOT IN`, `NOT BETWEEN`.

`LIKE` usa `%` e `_`. A barra invertida é o escape. Outro caractere se declara com `ESCAPE`:

```sql
SELECT nome FROM cliente WHERE nome LIKE '%\_%' ESCAPE '\';
```

Operadores: `||` concatena texto; `+`, `-`, `*`, `/` e `%` calculam. Funções: `LOWER`, `UPPER`, `LENGTH`, `CHAR_LENGTH`, `COALESCE`, `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`. `COUNT` aceita `*` e `DISTINCT`.

Contexto, no papel do Firebird: `CURRENT_DATE`, `CURRENT_TIME`, `CURRENT_TIMESTAMP`, `LOCALTIME`, `LOCALTIMESTAMP`, `CURRENT_USER`, `USER`, `CURRENT_ROLE`, `CURRENT_CONNECTION` e `CURRENT_TRANSACTION`. Não há fuso, então `CURRENT_TIME` e `LOCALTIME` são o mesmo `TIME`, e `CURRENT_TIMESTAMP` e `LOCALTIMESTAMP` são o mesmo `TIMESTAMP`. A precisão vai de 0 a 3; o padrão é 0 na hora e 3 no carimbo. O valor fica estável durante o comando e, dentro de procedure ou trigger, durante a rotina mais externa. `CURRENT_USER` e `USER` são o usuário da conexão. `CURRENT_ROLE` é o papel escolhido no login, ou `NONE`. `CURRENT_TRANSACTION` permanece enquanto a transação explícita estiver aberta; sem `BEGIN`, cada comando ganha um número novo. Esse número recomeça quando o arquivo é aberto de novo. Um nome entre aspas, como `"USER"`, continua sendo coluna.

```sql
SELECT CURRENT_DATE;

SELECT CURRENT_DATE, CURRENT_TIMESTAMP, CURRENT_USER, CURRENT_ROLE
FROM NEX$DATABASE;

INSERT INTO log (quando) VALUES (CURRENT_TIMESTAMP);
SELECT LOCALTIME(3), CURRENT_TRANSACTION FROM NEX$DATABASE;
```

`COUNT`, `SUM`, `AVG`, `MIN` e `MAX` ocupam a lista inteira do `SELECT`. Misturar agregação com coluna solta falha, porque não há `GROUP BY`.

Fora do dialeto: `JOIN`, subconsulta, `UNION`, `GROUP BY`, `HAVING`, `INSERT ... SELECT`, procedimento e `SAVEPOINT`.

## ResultSet

O resultado é só leitura e cabe na memória. Dá para andar com `next`, `previous`, `first`, `last`, `absolute` e `relative`. `CONCUR_UPDATABLE` e `TYPE_SCROLL_SENSITIVE` são recusados.

Coluna por nome ou por índice, começando em 1. `wasNull()` vale depois de uma leitura nula. `Statement.setMaxRows` limita quantas linhas entram no resultado.

```java
try (ResultSet rows = statement.executeQuery(
        "SELECT id, nome FROM cliente ORDER BY id")) {
    rows.last();
    int total = rows.getRow();
    rows.beforeFirst();
    while (rows.next()) {
        long id = rows.getLong(1);
        String nome = rows.getString("nome");
    }
}
```

`ResultSetMetaData` expõe nome, tipo SQL, `Types`, tamanho, escala, anulável e autoincremento.

## Transação

Com auto-commit ligado, cada comando é confirmado sozinho. Desligar o auto-commit abre uma transação no primeiro `INSERT`, `UPDATE`, `DELETE` ou `SELECT` seguinte. `commit()` e `rollback()` a encerram. Voltar o auto-commit para `true` confirma a transação desta conexão. Fechar a conexão com transação aberta desfaz.

```java
connection.setAutoCommit(false);
try (PreparedStatement update = connection.prepareStatement(
        "UPDATE cliente SET nome = ? WHERE id = ?")) {
    update.setString(1, "Ana Silva");
    update.setLong(2, 1);
    update.executeUpdate();
    connection.commit();
} catch (SQLException exception) {
    connection.rollback();
    throw exception;
} finally {
    connection.setAutoCommit(true);
}
```

O mesmo efeito pelo SQL, ainda com auto-commit ligado:

```sql
BEGIN;
UPDATE cliente SET nome = 'Ana Silva' WHERE id = 1;
COMMIT;
```

`ROLLBACK` no lugar de `COMMIT` descarta. `START TRANSACTION` também abre. `BEGIN` e `COMMIT` aceitam `WORK` ou `TRANSACTION` depois.

`setReadOnly(true)` deixa passar `SELECT` e os comandos de transação. O resto falha com SQLSTATE `25006`.

## Várias conexões

No mesmo processo, várias conexões `jdbc:nexora:` do mesmo arquivo compartilham o banco. Cada conexão informa usuário e senha. A propriedade `roleName` (ou `role`) escolhe o papel; vazio ou `NONE` entra sem papel. Outro processo recebe erro de banco já aberto.

`DROP CONNECTION n` derruba a conexão ativa de número `n`, o mesmo de `CURRENT_CONNECTION`. O DBA derruba qualquer uma. Outro usuário só derruba a própria. A conexão derrubada passa a `CLOSED` e o próximo comando nela falha. Se ela era a dona da transação aberta, o banco desfaz essa transação.

Só uma transação fica aberta. Enquanto ela existe, as outras conexões leem, inclusive o que ainda não foi confirmado, e não gravam. A tentativa de gravar falha com a mensagem de transação aberta.

`setTransactionIsolation` aceita `READ_UNCOMMITTED`, `READ_COMMITTED`, `REPEATABLE_READ` e `SERIALIZABLE`. O valor fica registrado na conexão. A visibilidade entre conexões do mesmo processo continua a descrita acima. `TRANSACTION_NONE` é recusado.

## Lote

```java
try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO cliente (nome, email) VALUES (?, ?)")) {
    insert.setString(1, "Bia");
    insert.setString(2, "bia@exemplo.com");
    insert.addBatch();
    insert.setString(1, "Caio");
    insert.setString(2, "caio@exemplo.com");
    insert.addBatch();
    int[] counts = insert.executeBatch();
}
```

No `Statement`, `addBatch` recebe o SQL e `executeBatch` executa cada um. `SELECT` no lote falha. Se um comando do meio falha, a exceção é `BatchUpdateException` e o vetor traz as contagens já obtidas.

## Metadados

Catálogo é `null`. O esquema padrão é `public`. Padrão de nome usa `%` e `_`, sem distinguir maiúsculas: `"PUBLIC"` casa com `public`. Tabelas `NEX$` aparecem como `SYSTEM TABLE`. `TABLE` devolve só as tabelas criadas pela aplicação.

```java
DatabaseMetaData meta = connection.getMetaData();
meta.getDatabaseProductName();   // Nexora
meta.getDriverName();            // Nexora JDBC

try (ResultSet tables = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
    while (tables.next()) {
        String table = tables.getString("TABLE_NAME");
    }
}

try (ResultSet columns = meta.getColumns(null, null, "cliente", "%")) {
    while (columns.next()) {
        String name = columns.getString("COLUMN_NAME");
        String type = columns.getString("TYPE_NAME");
        int size = columns.getInt("COLUMN_SIZE");
        String nullable = columns.getString("IS_NULLABLE");
        String auto = columns.getString("IS_AUTOINCREMENT");
    }
}

try (ResultSet keys = meta.getPrimaryKeys(null, null, "cliente")) {
    while (keys.next()) {
        String column = keys.getString("COLUMN_NAME");
        short position = keys.getShort("KEY_SEQ");
        String name = keys.getString("PK_NAME"); // PRIMARY
    }
}

try (ResultSet indexes = meta.getIndexInfo(null, null, "cliente", false, false)) {
    while (indexes.next()) {
        String name = indexes.getString("INDEX_NAME");
        boolean nonUnique = indexes.getBoolean("NON_UNIQUE");
        String column = indexes.getString("COLUMN_NAME");
        short position = indexes.getShort("ORDINAL_POSITION");
    }
}
```

`getIndexInfo(..., true, ...)` devolve só índices únicos, entre eles `PRIMARY` e cada `UNIQUE`. `getTypeInfo()` lista os tipos da tabela acima. `TABLE_SCHEM` traz o esquema de cada tabela. `getSchemas()` lista os esquemas, começando por `public`. Um esquema que não existe, como `"outro"`, deixa o resultado vazio.

## Erros

Falha de SQL vira `SQLException`. Mensagem de sintaxe ou de regra do banco usa SQLSTATE `42000`. Conexão fechada usa `08003`. `commit` ou `rollback` com auto-commit ligado usa `25000`. URL sem arquivo usa `08001`. Usuário ou senha em branco usa `28000`. Recurso fora do driver, como `CallableStatement`, `CLOB`, `ARRAY` ou savepoint, lança `SQLFeatureNotSupportedException`. Procedure roda por SQL, com `EXECUTE PROCEDURE`.

## Arquivo e tabelas internas

O arquivo começa com a página 0, o cabeçalho, e o catálogo na página seguinte. Cada tabela é uma região de páginas inteiras; o `BLOB` continua na região da própria tabela. Não há pasta de tabelas, nem arquivo de lock ou de commit ao lado: o bloqueio e a recuperação ficam no `.xdb`. O tamanho da página é o escolhido em `create`; se o JDBC cria o arquivo, a página é 8192.

Treze tabelas nascem com o arquivo. Um arquivo antigo ganha, ao abrir, as que ainda não existirem, entre elas `NEX$SCHEMAS` com a linha `public`. `CREATE TABLE` acrescenta o dono em `NEX$PRIVILEGES`. O prefixo `NEX$` é reservado. `DROP`, `ALTER` e `CREATE INDEX` nelas falham. Identificadores podem usar `$`.

| Tabela | Papel | Gravação |
|---|---|---|
| `NEX$DATABASE` | nome, descrição, dono, data, versão do arquivo e `NEX$PAGE_SIZE` | somente leitura |
| `NEX$CONFIG` | chave e valor (`Auth.Hash`, `Default.CharacterSet`) | DBA |
| `NEX$USERS` | usuários, senha com sal, ativo e administrador | DBA |
| `NEX$PRIVILEGES` | o que cada usuário pode fazer em cada tabela | `GRANT` e `REVOKE` |
| `NEX$CONNECTIONS` | cada conexão: usuário, entrada, saída e estado | o próprio banco |
| `NEX$GENERATORS` | geradores e sequences: valor atual, início, passo e dono | `CREATE` / `SET GENERATOR` |
| `NEX$PROCEDURES` | nome, código e dono da procedure | `CREATE PROCEDURE` |
| `NEX$PROCEDURE_PARAMETERS` | argumentos e retornos (`NEX$PARAMETER_TYPE` 0 = entrada, 1 = saída) | `CREATE PROCEDURE` |
| `NEX$TRIGGERS` | nome, tabela, posição, fase, código, inativo e dono | `CREATE TRIGGER` |
| `NEX$VIEWS` | nome, `SELECT`, relação de base, colunas e dono | `CREATE VIEW` |
| `NEX$ROLES` | nome e dono do papel | `CREATE ROLE` |
| `NEX$ROLE_MEMBERS` | quem recebeu o papel e se pode repassar | `GRANT papel TO usuário` |
| `NEX$SCHEMAS` | nome e dono de cada esquema; `public` é o padrão | `CREATE SCHEMA` / `DROP SCHEMA` |

O DBA inicial é `DBA`, senha `dba`, administrador e ativo. Outro usuário entra com `INSERT` em `NEX$USERS`; a coluna `NEX$PASSWORD` recebe a senha em texto e o banco grava bcrypt. `SELECT` devolve o hash, não a senha. Não dá para apagar ou desativar o último DBA.

```sql
INSERT INTO NEX$USERS (NEX$USER_NAME, NEX$PASSWORD) VALUES ('ana', 'ana');
UPDATE NEX$USERS SET NEX$PASSWORD = 'nova' WHERE NEX$USER_NAME = 'DBA';
SELECT NEX$USER_NAME, NEX$STATE, NEX$CONNECTED_AT FROM NEX$CONNECTIONS;
```

`NEX$STATE` fica `ACTIVE` enquanto a conexão está aberta e `CLOSED` ao fechar. Se o processo cair, a próxima abertura marca as conexões ainda ativas como `CLOSED`.

## Autorização

O DBA passa em qualquer tabela. Quem cria uma tabela vira dono e pode ler, gravar, alterar a estrutura e conceder acesso. Os outros usuários só entram com `GRANT`.

`NEX$PRIVILEGES` guarda usuário, tabela, privilégio (`OWNER`, `SELECT`, `INSERT`, `UPDATE` ou `DELETE`), se pode repassar (`NEX$GRANT_OPTION`) e quem concedeu (`NEX$GRANTOR`). `INSERT` direto nessa tabela falha. `PUBLIC` vale para todo mundo.

```sql
GRANT SELECT ON cliente TO ana;
GRANT SELECT, INSERT ON cliente TO ana WITH GRANT OPTION;
GRANT ALL ON cliente TO PUBLIC;
REVOKE INSERT ON cliente FROM ana;
REVOKE ALL ON cliente FROM PUBLIC;
```

`ALL` cobre `SELECT`, `INSERT`, `UPDATE` e `DELETE`. Não tira a posse. `GRANT` e `REVOKE` não rodam no meio de uma transação.

Um papel recebe privilégio de tabela e é concedido a um usuário. No login, só vale o papel informado; sem papel, `CURRENT_ROLE` fica `NONE` e o privilégio do papel não entra. O DBA usa qualquer papel existente. Quem cria o papel é o dono e pode concedê-lo. `WITH ADMIN OPTION` deixa o usuário repassar o papel. `PUBLIC` também pode receber o papel. O nome do papel não pode coincidir com um usuário, nem ser `NONE` ou `PUBLIC`.

```sql
CREATE ROLE vendas;
GRANT SELECT ON cliente TO vendas;
GRANT vendas TO ana WITH ADMIN OPTION;
REVOKE vendas FROM ana;
DROP ROLE vendas;
```

```java
Session ana = db.login("ana", "ana", "vendas");
```

## Geradores, procedures e triggers

O comportamento segue o Firebird. O valor de um gerador é confirmado na hora: `ROLLBACK` desfaz a linha, mas não o `GEN_ID`. `CREATE GENERATOR` começa em 0, então o primeiro `GEN_ID(nome, 1)` devolve 1. `SET GENERATOR nome TO 100` grava 100; o próximo passo 1 devolve 101. `CREATE SEQUENCE nome START WITH 10 INCREMENT BY 2` faz o primeiro `NEXT VALUE FOR nome` devolver 10. `ALTER SEQUENCE nome RESTART WITH 20` faz o próximo valor ser 20. `GEN_ID(nome, 0)` só lê o valor atual. Quem cria o gerador é o dono; o DBA também pode apagar.

```sql
CREATE GENERATOR gen_item;
CREATE SEQUENCE seq_item START WITH 10 INCREMENT BY 2;
SELECT GEN_ID(gen_item, 1) AS n FROM NEX$DATABASE;
SELECT NEXT VALUE FOR seq_item AS n FROM NEX$DATABASE;
SET GENERATOR gen_item TO 100;
ALTER SEQUENCE seq_item RESTART WITH 20;
DROP GENERATOR gen_item;
```

Procedure guarda o código e os parâmetros. No corpo, o ponto e vírgula faz parte da rotina: não é preciso `SET TERM`. `EXECUTE PROCEDURE` devolve uma linha com os retornos, parando no primeiro `SUSPEND`. `SELECT` em `nome(argumentos)` devolve uma linha por `SUSPEND`. Sem `SUSPEND`, o `SELECT` vem vazio e o `EXECUTE PROCEDURE` ainda devolve os valores finais. Dentro do SQL da rotina, variável usa `:nome`. Em `IF`, `WHILE` e atribuição, o nome sozinho já é a variável. `NEW` e `OLD` existem no trigger. `OLD` só é lido. Atribuição em `BEFORE INSERT` ou `BEFORE UPDATE` entra na linha; em `AFTER`, não muda o que já foi gravado. `DELETE` só tem `OLD`.

```sql
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

SELECT n FROM serie(3);

CREATE TRIGGER item_bi FOR item
ACTIVE BEFORE INSERT POSITION 0
AS
BEGIN
  IF (NEW.id IS NULL) THEN
    NEW.id = GEN_ID(gen_item, 1);
END

ALTER TRIGGER item_bi INACTIVE;
DROP TRIGGER item_bi;
```

O corpo aceita `DECLARE VARIABLE`, `DECLARE cursor CURSOR FOR (SELECT ...)`, `OPEN`, `FETCH ... INTO`, `CLOSE`, `FOR SELECT ... INTO ... DO`, `BEGIN/END`, atribuição, `IF/THEN/ELSE`, `WHILE/DO`, `EXIT`, `SUSPEND`, `EXCEPTION 'texto'`, `INSERT`, `UPDATE`, `DELETE`, `EXECUTE PROCEDURE`, `SELECT ... INTO` e `RETURNING_VALUES`. `ROW_COUNT` vale 1 depois de um `FETCH` com linha e 0 quando o cursor acaba; também recebe a quantidade de linhas do último `INSERT`, `UPDATE`, `DELETE` ou `SELECT INTO` da rotina. O `SELECT` do cursor enxerga `:variavel` e `NEW`/`OLD` na hora do `OPEN`. `FETCH` sem linha não muda as variáveis. Abrir um cursor aberto, ou ler e fechar um cursor fechado, falha. Posição menor roda primeiro. Trigger inativo não roda. `DROP TABLE` apaga os triggers da tabela. Procedure e trigger encadeados param em 32 chamadas. O dono ou o DBA altera o objeto; trigger na tabela também exige ser dono dela ou DBA. `INSERT` direto nessas tabelas de catálogo falha.

```sql
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

FOR SELECT nome FROM item ORDER BY id
INTO nome
DO SUSPEND;
```

View é um `SELECT` guardado, de uma tabela ou de outra view. `SELECT` nela aplica `WHERE`, lista de colunas, `DISTINCT`, `ORDER BY` e `LIMIT` por cima do resultado. `INSERT`, `UPDATE` e `DELETE` falham: a view é somente leitura. O nome não pode coincidir com uma tabela. `DROP TABLE` ou `DROP VIEW` falha enquanto outra view depender do objeto. O dono ou o DBA remove a view. `GRANT` e `REVOKE` usam o nome dela, com `ON` ou `ON VIEW`. `SELECT *` usa as colunas gravadas na criação. JDBC lista o tipo `VIEW`.

```sql
CREATE VIEW ativo (pessoa) AS
SELECT nome FROM item WHERE ativo = TRUE;

SELECT pessoa FROM ativo WHERE pessoa <> 'Caio' ORDER BY pessoa;
SELECT COUNT(*) FROM ativo;

CREATE VIEW nomes AS SELECT pessoa FROM ativo;
DROP VIEW nomes;
DROP VIEW ativo;
```
