# Nexora — JDBC

Nexora JDBC driver. The database is a pair of files: the `.db` holds the tables, the `BLOB`s, the configuration, the users, and the connection registry; the `.log` beside it holds the transactions. The application talks to it through `java.sql`, with `DriverManager`, a connection pool, or DBeaver.

Java 21. The driver declares JDBC 4.2 and is not a certified driver (`jdbcCompliant()` returns `false`).

There are eight Maven projects. `nexora-engine` has the `.db` file, the `.log`, the SQL, and the protocol. `nexora-jdbc` has the driver, with an embedded connection and a connection to the server. `nexora-dbsrv` is the network server: it holds the files, listens on the network, and opens the message console. `nexora-dbeng` is the personal server window and uses that same listener on this machine. `nexora-cli` creates the database. `nexora-studio` is the administrative interface. `nexora-backup` is the backup window. `nexora-dbtran` is the log translation window. The driver, the network server, the CLI, the studio, the backup window, and the log translation window depend on the engine. The personal server depends on the network server. The driver and the servers do not depend on each other. The studio and the backup window depend on the driver. The CLI and the log translation window depend only on the engine.

## Installation

From the root, Maven builds the engine and then the driver, the network server, the personal server, the CLI, the studio, the backup window, and the log translation window.

```bash
mvn package
```

The network server is the JAR `nexora-dbsrv/target/nexora-dbsrv-1.0.jar`, with the engine inside. The personal server is the JAR `nexora-dbeng/target/nexora-dbeng-1.0.jar`. Both open the message console, as in [Network](#network). The CLI is the JAR `nexora-cli/target/nexora-cli-1.0.jar`, also with the engine inside. It creates the database, as in [Create the database](#create-the-database). The studio is the JAR `nexora-studio/target/nexora-studio-1.0.jar`, with the driver and the engine inside. It opens the interface, as in [Studio](#studio). The backup window is the JAR `nexora-backup/target/nexora-backup-1.0.jar`, with the driver and the engine inside. It copies the database from the window or from the command line, as in [Backup window](#backup-window). The log translation window is the JAR `nexora-dbtran/target/nexora-dbtran-1.0.jar`, with the engine inside. It writes the SQL from the log, as in [Log translation](#log-translation).

`mvn install` publishes the driver to the local repository. The application depends only on the driver:

```xml
<dependency>
    <groupId>io.github.gabrielmmoraes1999</groupId>
    <artifactId>nexora-jdbc</artifactId>
    <version>1.0</version>
</dependency>
```

The `META-INF/services/java.sql.Driver` service registers the driver. `DriverManager.getConnection` finds it without `Class.forName`.


|              |                                                      |
| ------------ | ---------------------------------------------------- |
| Class        | `io.github.gabrielmmoraes1999.nexora.jdbc.NexDriver` |
| Embedded URL | `jdbc:nexora:embedded:<file.db>`                     |
| Network URL  | `jdbc:nexora://<host>:<port>/<name>`                 |
| User         | `dba`                                                |
| Password     | `dba`                                                |
| Product      | Nexora 1.0                                           |


```java
String url = "jdbc:nexora:embedded:C:/data/store.db";
try (Connection connection = DriverManager.getConnection(url, "dba", "dba")) {
    // ...
}
```

`jdbc:nexora:C:/data/store.db` remains valid. `jdbc:nexora://C:/data/store.db` does too: the slash before the drive letter is ignored. A URL with no file (`jdbc:nexora:`) fails. For another process, use the network URL described in [Network](#network). A blank user or password fails with SQLSTATE `28000`. A wrong password fails with the invalid user or password message.

The `.db` file already exists. JDBC and the server do not create it. The page size stays fixed, chosen at creation by the CLI.

The password is not stored in plain text. Each write uses bcrypt (`$2a$`, cost 10). `Connection.getMetaData().getUserName()` returns the authenticated user, in uppercase.

`getCatalog()` returns `null`. `getSchema()` returns the session schema, `public` on connect. `setSchema` changes that schema.

## Create the database

The CLI `create` command generates the `.db`, the `.log` beside it, and the DBA user. The page size is `4096`, `8192`, `16384`, or `32768`. The default is `8192`. It does not change after creation. Page 0 is the header; the catalog and the tables occupy whole pages. Commit writes the `.log` before the pages and returns only after that commit record is durable. The checkpoint synchronizes dirty pages to the `.db`, including pages of a transaction that is still open, and leaves the history in the log. Storing those pages does not commit the transaction. If the process crashes, opening reapplies committed work that is still ahead of the checkpoint and undoes transactions that were stored but not committed. A torn record at the end of the log is discarded. Every open and every close writes the checkpoint. An automatic checkpoint also runs from time, dirty pages, log growth, or cache pressure; those thresholds do not commit an open transaction. If the `.log` is not beside the database, or is empty, opening creates a new log with only the header, advances the generation, and the database continues with the pages already written. A log from the previous generation does not open the database. The log is not cut at a checkpoint. A full backup is what starts a new generation. A checkpoint uses the same lock as SQL, so it does not run beside a statement. Two open transactions cannot change the same table. The before-image of a large value is stored whole in the log, and the log keeps growing until a full backup.

```bash
java -jar nexora-cli/target/nexora-cli-1.0.jar create C:/data/store.db --page-size 16384 --user DBA --password secret --name STORE --charset UTF8
```


| Option          | Default                          |
| --------------- | -------------------------------- |
| `--page-size`   | `8192`                           |
| `--user`        | `DBA`                            |
| `--password`    | `dba`                            |
| `--name`        | file name, without the extension |
| `--description` | `Nexora`                         |
| `--charset`     | `UTF8`                           |


A file that already has content is not rewritten, and neither is the corresponding `.log`. With no arguments, or with `--help`, the command shows the usage. In the API, the same effect is `Database.create(file, options)`.

The log accumulates the committed transactions since the database was created or the last full backup. Each commit writes the command and the values compressed, so the file shows neither the SQL nor the contents of the `INSERT`s. A full backup copies the `.db` and the `.log` to another folder and, on the live database, the log then records only what changes after that copy. An incremental backup copies only the `.log`, and the live log continues from where it is. Applying a log decompresses those transactions and runs the SQL against a `.db`: the same generation continues from the checkpoint, and the following generation brings what changed after the full backup.

`nexora-backup` copies the files, as in [Backup window](#backup-window). The personal server and the network server apply a log with `-a`, as in [Network](#network). `nexora-dbtran` writes the SQL from the log, as in [Log translation](#log-translation).

In the API, `Database.backup(file, folder)` performs the full backup, `Database.backupLog(file, folder)` copies only the log, `Database.applyLog(file, log)` applies it, and `Database.exportSql(log, file.sql)` exports. `exportSql` also accepts the `.db` and reads the `.log` beside it. `applyLog` accepts only the log of the same database, including the backup copy: the identifier stored in the log must belong to that `.db`. The backup folder stays outside the database folder.

`NEX$PAGE_SIZE` stores the page size. `NEX$FORMAT` is `1`. `Default.CharacterSet` in `NEX$CONFIG` stores the character set supplied at creation.

## Backup window

`nexora-backup` copies the database. With no arguments, a window opens. With a source and a directory, the command writes the copy and prints the percentage while it runs. The source is a `.db` file or a JDBC URL. A JDBC URL needs `--user` and `--password`, and that user must be a DBA. `--role` is optional. `jdbc:nexora:embedded:<file.db>` opens the file in this process. `jdbc:nexora://host:port/name` asks the server that holds the file to send the copy; the directory is on the machine running the command. A full backup resets the live log after the files have been written. An incremental backup copies only the `.log` and leaves the live log in place. With no kind, the copy is full. The window stores the last fields in `~/.nexora/backup.properties`. The password is written there only when Remember password is checked. `--help` shows the usage.

```bash
java -jar nexora-backup/target/nexora-backup-1.0.jar
java -jar nexora-backup/target/nexora-backup-1.0.jar full C:/data/store.db C:/data/backup
java -jar nexora-backup/target/nexora-backup-1.0.jar incremental C:/data/store.db C:/data/logs
java -jar nexora-backup/target/nexora-backup-1.0.jar full jdbc:nexora://127.0.0.1:4580/store --user dba --password secret C:/data/backup
```

## Log translation

`nexora-dbtran` writes the SQL from the transaction log. With no arguments, a window opens. With a log and a SQL file, the command writes the script and prints the percentage while it reads the file. The source is a `.log` or the `.db` beside it. Parameters are written into the text in place of each `?`. The window stores the last fields in `~/.nexora/dbtran.properties`. `--help` shows the usage.

```bash
java -jar nexora-dbtran/target/nexora-dbtran-1.0.jar
java -jar nexora-dbtran/target/nexora-dbtran-1.0.jar C:/data/store.log C:/data/store.sql
java -jar nexora-dbtran/target/nexora-dbtran-1.0.jar C:/data/store.db C:/data/store.sql
```

## Network

The `.db` file stays open in a single process, together with the `.log`. That process is `nexora-dbeng` on this machine, or `nexora-dbsrv` for other machines. Each one listens on a port and serves JDBC. The driver, in the `nexora-jdbc` project, talks to it through `jdbc:nexora://host:port/name` and also opens the file directly with `jdbc:nexora:embedded:`. The password travels in the clear on TCP, intended for the local network.

`nexora-dbeng` is the personal server. `nexora-dbsrv` is the network server. With no database on the command line, the first window asks for the database file, the cache size, and the options. An empty options field starts the database. `-a banco.log` applies that log and the process exits when the apply finishes. `-x tcpip(port=4580)` changes the port. OK opens the message console, with a Shutdown button. OK also stores those three fields. The next time the window opens with no database and no options on the command line, it shows them again. The personal server writes `~/.nexora/dbeng.properties`. The network server writes `~/.nexora/dbsrv.properties`. A database file on the command line still skips the window and leaves the stored fields as they were.

After the database is started, the process stays in the background and an icon sits in the Windows notification area. Closing or minimizing the console hides it there. Double-click the icon, or Open, brings the console back. Shutdown stops the server and releases the files. Server, then Log SQL, writes each statement on the console.

The personal server listens on `127.0.0.1` only. The network server listens on all interfaces unless `--host` says otherwise. The default port is `4580`. The default cache size is `8M`. That size is the maximum memory for database pages. It is rounded up to a whole number of pages. When the cache is full, the oldest pages are dropped and read again from the file. A `-n` after the database file, or `file.db=name`, is the name in the JDBC URL. A database file on the command line skips the first window and opens the console directly. Each file already exists, created by the CLI.

```bash
java -jar nexora-dbeng/target/nexora-dbeng-1.0.jar -p 4580 C:/data/store.db
java -jar nexora-dbsrv/target/nexora-dbsrv-1.0.jar -x tcpip(port=4580) C:/data/store.db C:/data/other.db
```

On the client:

```java
String url = "jdbc:nexora://192.168.0.10:4580/store";
try (Connection connection = DriverManager.getConnection(url, "dba", "dba")) {
    // ...
}
```

On this machine the personal server is `jdbc:nexora://127.0.0.1:4580/store`. The name after the port is the name from `-n` or from the file name, with no case distinction. Several connections on the server follow the rule of one transaction per file. While the server holds the `.db`, another process that tries to open the same file receives database already open. Shutdown releases the files.

## Studio

The studio is the administrative screen, in the spirit of IBExpert: an explorer on the left, documents in the center, and the grid below the SQL. It registers a local file and a database on the server, creates the `.db`, runs a script, and opens a table, a view, a procedure, a trigger, a generator, a role, a user, a privilege, and a connection.

```bash
java -jar nexora-studio/target/nexora-studio-1.0.jar
```

F9 runs the script in the editor. Ctrl+Enter does the same. A `SELECT` has to be the last command, as in JDBC. The grid writes to tables that have a primary key. An empty cell becomes `NULL`; for `CHAR` and `VARCHAR`, `''` stores empty text. On the Columns tab of a table, Move up and Move down run `ALTER COLUMN ... POSITION` and the data grid follows that order.

The embedded connection opens the `.db` in this process. If the server already holds the file, it fails: register the database over the network, with the host, the port, and the name the server is publishing. The registration is stored in `~/.nexora/studio.json`. The password is written to that file only when "remember password" is checked.

## DBeaver

1. New connection, custom driver.
2. Class: `io.github.gabrielmmoraes1999.nexora.jdbc.NexDriver`.
3. URL: `jdbc:nexora:C:/data/store.db`.
4. User `dba`, password `dba`.
5. Libraries: the driver JAR (`nexora-jdbc/target/nexora-jdbc-1.0.jar`). The engine is inside that JAR. In DBeaver, replace the old JAR with this one. The personal server and the network server are not added to DBeaver.

Opening an embedded database loads the catalog, table metadata, and row locations. Clean row values are decoded on access through the bounded page cache; scanning a table does not retain every decoded row in the engine. Row locations still use memory proportional to the number of rows. Modified rows and transaction snapshots that must survive page reuse can also use additional memory. Large query results are materialized by the current query API.



## Example

The file `C:/data/store.db` has already been created by the CLI.

```java
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

Path file = Path.of("C:/data/store.db");

try (Connection connection = DriverManager.getConnection("jdbc:nexora:" + file.toAbsolutePath(), "dba", "dba")) {
    try (Statement statement = connection.createStatement()) {
        statement.execute("""
                CREATE TABLE customer (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name VARCHAR(120) NOT NULL,
                    email VARCHAR(120) UNIQUE,
                    birth_date DATE,
                    photo BLOB
                )
                """);
        statement.execute("CREATE INDEX idx_name ON customer (name)");
    }

    byte[] photo = Files.readAllBytes(Path.of("ana.jpg"));
    try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO customer (name, email, birth_date, photo) VALUES (?, ?, ?, ?)")) {
        insert.setString(1, "Ana");
        insert.setString(2, "ana@example.com");
        insert.setDate(3, Date.valueOf("1990-04-12"));
        insert.setBytes(4, photo);
        insert.executeUpdate();
    }

    try (PreparedStatement query = connection.prepareStatement(
            "SELECT id, name, photo FROM customer WHERE email = ?")) {
        query.setString(1, "ana@example.com");
        try (ResultSet rows = query.executeQuery()) {
            if (rows.next()) {
                long id = rows.getLong("id");
                String name = rows.getString("name");
                byte[] bytes = rows.getBytes("photo");
            }
        }
    }
}
```



## SQL

Several commands can go in the same `execute`, separated by `;`. A `SELECT` has to be the last one. The `?` placeholders are positional and apply to the whole script. Comments are `--` through the end of the line and `/* ... */`.

Unquoted identifiers are case-insensitive. Quoted identifiers preserve case, and an embedded `"` is written `""`. A table, column, or index name is at most 128 characters. `$` is allowed in a name, as in `NEX$USERS`.

### Types


| SQL                                      | JDBC `Types` | Literal / `PreparedStatement`                                      |
| ---------------------------------------- | ------------ | ------------------------------------------------------------------ |
| `SMALLINT`                               | `SMALLINT`   | integer, `setShort`                                                |
| `INTEGER` or `INT`                       | `INTEGER`    | integer, `setInt`                                                  |
| `BIGINT`                                 | `BIGINT`     | integer, `setLong`                                                 |
| `FLOAT` or `REAL`                        | `REAL`       | `setFloat`                                                         |
| `DOUBLE` or `DOUBLE PRECISION`           | `DOUBLE`     | `setDouble`                                                        |
| `DECIMAL(p, s)` or `NUMERIC(p, s)`       | `DECIMAL`    | `12.50`, `setBigDecimal`                                           |
| `BOOLEAN` or `BOOL`                      | `BOOLEAN`    | `TRUE`, `FALSE`, `setBoolean`                                      |
| `CHAR(n)` or `CHARACTER(n)`              | `CHAR`       | `'text'`, `setString`                                              |
| `VARCHAR(n)` or `CHARACTER VARYING(n)`   | `VARCHAR`    | `'text'`, `setString`                                              |
| `DATE`                                   | `DATE`       | `'2026-10-07'`, `setDate`                                          |
| `TIME`                                   | `TIME`       | `'13:45'` or `'13:45:01'`, `setTime`                               |
| `TIMESTAMP`                              | `TIMESTAMP`  | `'2026-10-07 13:45:01'` or `'2026-10-07T13:45:01'`, `setTimestamp` |
| `BLOB`, `BYTEA`, `BINARY`, or `VARBINARY` | `BLOB`       | `X'0102'`, `X''`, `setBytes`                                       |


`CHAR` and `VARCHAR` require a length from 1 to 8192. `DECIMAL` requires a precision from 1 to 38, and the scale does not exceed the precision. A table has at most 1024 columns.

`NULL` is null. Single quotes inside text are doubled: `'it''s'`. `X'0102'` is binary; the number of hexadecimal digits must be even.

```java
prepared.setNull(4, Types.BLOB);
prepared.setObject(1, "Ana");
```

The parameter index starts at 1.

### Tables

```sql
CREATE TABLE orders (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    customer_id INTEGER NOT NULL,
    total DECIMAL(12, 2) DEFAULT 0,
    open BOOLEAN DEFAULT TRUE,
    created TIMESTAMP
);

CREATE TABLE item (
    order_id INTEGER NOT NULL,
    number INTEGER NOT NULL,
    product VARCHAR(80) NOT NULL,
    quantity INTEGER DEFAULT 1,
    PRIMARY KEY (order_id, number),
    UNIQUE (order_id, product)
);

CREATE TABLE IF NOT EXISTS orders (
    id INTEGER PRIMARY KEY
);

ALTER TABLE orders ADD COLUMN notes VARCHAR(200) DEFAULT '';
ALTER TABLE orders DROP COLUMN notes;
ALTER TABLE orders ALTER notes POSITION 1;

CREATE TABLE note (
    id INTEGER NOT NULL,
    text VARCHAR(80)
);

ALTER TABLE note ADD PRIMARY KEY (id);
ALTER TABLE note DROP PRIMARY KEY;
DROP TABLE IF EXISTS draft;
DROP TABLE orders;
```

`ALTER [COLUMN] ... POSITION` moves a column. Positions start at 1. The columns in between shift. `SELECT *`, `INSERT` without a column list, and the catalog follow the new order. The values stay on the column. A view keeps the column order it had when it was created.

A table has one primary key. It can be a single column (`id INTEGER PRIMARY KEY`) or composite (`PRIMARY KEY (a, b)`), at creation or later with `ALTER TABLE ... ADD PRIMARY KEY`. The index is created with it, with the constraint name and Ascending order. Without an explicit name, both are called `PK_` plus the table name. `ADD CONSTRAINT PK_orders PRIMARY KEY (id)` stores that name. For another name or descending order:

```sql
ALTER TABLE test
  ADD CONSTRAINT PK_test PRIMARY KEY (id) USING DESCENDING INDEX ix_test;
```

`USING INDEX` without `DESCENDING` remains Ascending. The physical order of the index stays ascending; `DESCENDING` is recorded on the constraint and appears in `ASC_OR_DESC`. The table accepts only one key. The key columns become `NOT NULL`. If those columns already contain a null or a duplicate value, the command fails and the table stays as it was. `DROP PRIMARY KEY` and `DROP CONSTRAINT name` remove the key and the index tied to it. The columns remain `NOT NULL`. If a foreign key still points at that primary key, and there is no `UNIQUE` on the same columns, the command fails and the table stays as it was. `AUTOINCREMENT` and `IDENTITY` apply to `SMALLINT`, `INTEGER`, and `BIGINT`, and the first value is 1. `UNIQUE` can be on the column or on the table and creates an index with the same name as the constraint. Without a name, the first is `UNQ1_` plus the table name, the next is `UNQ2_` plus the table name, and so on. `CONSTRAINT UQ_email UNIQUE (email)` stores that name on the index. `DROP CONSTRAINT name` and `DROP UNIQUE name` remove the constraint and the index. If a foreign key still points at those columns, and there is no other key covering the same columns, the command fails and the table stays as it was. `DEFAULT` accepts a literal, `NULL`, `TRUE`, or `FALSE`. `NOT NULL` and `NULL` cannot be combined on the same column.

A foreign key also creates an index, with the `FK_` prefix. The default is `FK_` plus the table name, Ascending, and the index receives the same name as the constraint. The second key on the same table uses `FK_table_column`. Without the referenced column list, the other table's primary key applies. The reference must be the primary key or a `UNIQUE`, with the same type. A null in any key column does not require a row on the other side. `ON DELETE` and `ON UPDATE` accept `NO ACTION`, `RESTRICT`, `CASCADE`, `SET NULL`, and `SET DEFAULT`. Without the clause, `NO ACTION` applies: deleting or changing the referenced key fails while a child row exists. `CASCADE` repeats the deletion or the new key on the child rows. `SET NULL` writes null into the key columns, which must accept null. `SET DEFAULT` writes each column's default, which must have a `DEFAULT`.

```sql
CREATE TABLE item (
    id INTEGER PRIMARY KEY,
    order_id INTEGER,
    CONSTRAINT FK_item FOREIGN KEY (order_id) REFERENCES orders (id)
);

ALTER TABLE item
  ADD CONSTRAINT FK_item_extra FOREIGN KEY (extra) REFERENCES orders (id)
  USING DESCENDING INDEX ix_extra;

ALTER TABLE item DROP CONSTRAINT FK_item;
ALTER TABLE item DROP FOREIGN KEY FK_item_extra;
```

`DROP CONSTRAINT name` and `DROP FOREIGN KEY name` remove the foreign key and the linked index, even when the index has another name. `DROP INDEX` still does not remove an index of a `PRIMARY KEY`, `FOREIGN KEY`, or `UNIQUE`.

```sql
CREATE TABLE customer (
    id INTEGER PRIMARY KEY,
    email VARCHAR(120) UNIQUE,
    document VARCHAR(20),
    CONSTRAINT UQ_document UNIQUE (document)
);

ALTER TABLE customer DROP UNIQUE UNQ1_customer;
ALTER TABLE customer DROP CONSTRAINT UQ_document;
ALTER TABLE customer ADD CONSTRAINT UQ_email UNIQUE (email);
```

`getGeneratedKeys()` returns an empty result. The generated value is read with `SELECT`.

### Schemas

The default schema is `public`. A name without a schema uses the session schema. `schema.table` points at another schema. The same table name can exist in different schemas. `public` is created with the file and cannot be removed.

```sql
CREATE SCHEMA sales;
CREATE TABLE sales.orders (
    id INTEGER PRIMARY KEY
);
INSERT INTO sales.orders (id) VALUES (1);
SELECT id FROM sales.orders;
SELECT sales.orders.id FROM sales.orders;

SET SCHEMA sales;
SELECT id FROM orders;
SET SCHEMA public;

DROP SCHEMA sales RESTRICT;
DROP SCHEMA IF EXISTS sales CASCADE;
```

`CREATE SCHEMA IF NOT EXISTS` does not fail if the schema already exists. `DROP SCHEMA` without `CASCADE` requires the schema to be empty. `CASCADE` removes the tables, the views, and the procedures of that schema, and views outside it that depend on them. It does not remove the current session's schema: run `SET SCHEMA public` first. `GRANT` and `REVOKE` accept `schema.table`. `NEX$` tables stay in `public` and remain visible without qualification, in any session schema.

### Indexes

```sql
CREATE INDEX idx_customer ON orders (customer_id);
CREATE UNIQUE INDEX idx_email ON customer (email);
CREATE INDEX IF NOT EXISTS idx_item ON item (order_id, number);
DROP INDEX idx_customer;
DROP INDEX IF EXISTS idx_customer;
DROP INDEX sales.idx_customer;
DROP INDEX idx_customer ON orders;
```

`CREATE UNIQUE INDEX` rejects a duplicate, including in rows that already exist. A `BLOB` cannot be part of an index or a key. The names `PRIMARY` and `UNQ1_table`, `UNQ2_table`, … are reserved for the index of the `UNIQUE` declared on the table. The primary key index, each foreign key index, and each `UNIQUE` index are not removed by `DROP INDEX`. Dropping a column removes the indexes that use it.

An index name is unique within its schema. The primary key index, each `UNIQUE` index, each foreign key index, and each index from `CREATE INDEX` share that namespace. `CREATE INDEX idx ON public.customer` and `CREATE INDEX idx ON sales.customer` are both valid; a second `idx` in `public` fails. `CREATE INDEX IF NOT EXISTS` does nothing when that name is already an index in the schema, even when the index belongs to another table. A generated `UNQ` or `FK_` name that is already an index in the schema is skipped for the next free candidate. The default primary key index stays `PK_` plus the table name, and creation fails when that name is already an index in the schema.

`DROP INDEX` finds the index by that name in the schema. Without a schema, it uses the session schema. `sales.idx_customer` selects the schema. `ON table` is optional and limits the search to that table.

The entries live in 4096-byte pages in the table region. The file grows with the keys. An equality lookup reads only the pages on the path, from the root to the leaf, and the full key list does not stay in memory. The table rows are still loaded on open.

The plan uses the index when the `WHERE` has equalities joined by `AND`, with a literal or `?`, and those equalities cover a leftmost prefix of the index. An index on `(order_id, number)` is used for `order_id = 10` and for `order_id = 10 AND number = 2`. A predicate on `number` alone does not use that index.

```sql
SELECT id FROM customer WHERE email = ?;
SELECT * FROM item WHERE order_id = 10 AND number = 2;
```

`LIKE`, `OR`, a range, and an expression stay out of that lookup, and the table is scanned.

`CREATE`, `DROP`, and `ALTER` belong to the open transaction. `COMMIT` keeps them. `ROLLBACK` undoes them, so a dropped index comes back.

### Rows

```sql
INSERT INTO customer (name, email) VALUES ('Ana', 'ana@example.com');
INSERT INTO item (order_id, number, product, quantity)
VALUES (1, 1, 'Notebook', 2), (1, 2, 'Pencil', 10);

UPDATE customer SET name = ? WHERE id = ?;
DELETE FROM item WHERE order_id = 1 AND number = 2;
```

`INSERT ... SELECT` does not exist. The column list can be omitted when the values follow the table order.

### Queries

A query reads tables, views, and procedure calls, with an alias, `DISTINCT`, `WHERE`, `GROUP BY`, `HAVING`, `ORDER BY`, `LIMIT`, and `OFFSET`. `FROM` can be left out when the list uses no column: the result has one row, and a false `WHERE` returns none. `SELECT *` still requires a table. `GROUP BY` accepts expressions, a select-list ordinal, or an alias. A column outside an aggregate must be grouped. `HAVING` filters the groups and may use aggregates. Without `GROUP BY`, an aggregate query is still one group, including on an empty table. `GROUP BY` on an empty table returns no rows. `SELECT *` cannot be combined with `GROUP BY`.

```sql
SELECT id, name AS customer
FROM customer c
WHERE c.name LIKE 'A%' AND birth_date >= '1990-01-01'
ORDER BY name ASC, id DESC
LIMIT 20 OFFSET 40;

SELECT DISTINCT open FROM orders;
SELECT COUNT(*) FROM customer;
SELECT COALESCE(email, name) FROM customer WHERE id = 1;
SELECT UPPER(name), LENGTH(photo) FROM customer WHERE photo IS NOT NULL;
```

Predicates: `=`, `<>`, `!=`, `<`, `>`, `<=`, `>=`, `AND`, `OR`, `NOT`, `IS NULL`, `IS NOT NULL`, `IN (...)`, `BETWEEN ... AND ...`, `LIKE`, `NOT LIKE`, `NOT IN`, `NOT BETWEEN`.

`LIKE` uses `%` and `_`. The backslash is the escape. Another character is declared with `ESCAPE`:

```sql
SELECT name FROM customer WHERE name LIKE '%\_%' ESCAPE '\';
```

Operators: `||` concatenates text; `+`, `-`, `*`, `/`, and `%` calculate. Functions: `LOWER`, `UPPER`, `LENGTH`, `CHAR_LENGTH`, `COALESCE`, `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`. `COUNT` accepts `*` and `DISTINCT`.

Context values: `CURRENT_DATE`, `CURRENT_TIME`, `CURRENT_TIMESTAMP`, `LOCALTIME`, `LOCALTIMESTAMP`, `CURRENT_USER`, `USER`, `CURRENT_ROLE`, `CURRENT_CONNECTION`, and `CURRENT_TRANSACTION`. There is no time zone, so `CURRENT_TIME` and `LOCALTIME` are the same `TIME`, and `CURRENT_TIMESTAMP` and `LOCALTIMESTAMP` are the same `TIMESTAMP`. Precision runs from 0 to 3; the default is 0 for the time and 3 for the timestamp. The value stays stable during the command and, inside a procedure or trigger, during the outermost routine. `CURRENT_USER` and `USER` are the connection user. `CURRENT_ROLE` is the role chosen at login, or `NONE`. `CURRENT_TRANSACTION` stays the same while the explicit transaction is open; without `BEGIN`, each command gets a new number. That number starts over when the file is opened again. A quoted name, such as `"USER"`, is still a column.

```sql
SELECT CURRENT_DATE;

SELECT CURRENT_DATE, CURRENT_TIMESTAMP, CURRENT_USER, CURRENT_ROLE
FROM NEX$DATABASE;

INSERT INTO log (occurred_at) VALUES (CURRENT_TIMESTAMP);
SELECT LOCALTIME(3), CURRENT_TRANSACTION FROM NEX$DATABASE;
```

`COUNT`, `SUM`, `AVG`, `MIN`, and `MAX` can stand alone or sit next to grouped columns. Mixing an aggregate with a bare column fails when that column is not in the `GROUP BY`.

```sql
SELECT city, COUNT(*) AS total
FROM customer
GROUP BY city
HAVING COUNT(*) > 5
ORDER BY total DESC;
```

Joins run left to right. An equality in `ON` seeks an index of the right table when the join columns are a leftmost prefix of that index. When no index fits, that equality is hashed. A comparison that is not an equality, and `CROSS JOIN`, are nested loops. A filter that names only one side runs on that side before the join and can use an index there. `JOIN` and `INNER JOIN` are the same. `LEFT`, `RIGHT`, and `FULL` accept an optional `OUTER`. Those four require `ON`. `CROSS JOIN` does not. The condition uses the same predicates as `WHERE`. An unqualified column that appears in more than one input is an error. `SELECT *` concatenates the columns from left to right.

```sql
SELECT c.name, o.id
FROM customer c
JOIN orders o ON c.id = o.customer_id
LEFT JOIN item i ON i.order_id = o.id
WHERE c.city = 'Recife';
```

`UNION` removes duplicate rows. `UNION ALL` keeps them. Both sides need the same number of columns and the same inferred types. Names come from the first query. `ORDER BY`, `LIMIT`, and `OFFSET` apply to the combined result.

```sql
SELECT city FROM customer
UNION
SELECT city FROM supplier
ORDER BY city
LIMIT 10;
```

`WITH name AS (query)` defines a non-recursive common table expression. Several can appear in one query, and a later one can read an earlier one. An optional column list renames the output: `WITH name (a, b) AS (...)`. A CTE hides a table or view of the same name in that query. It cannot reference itself. `WITH RECURSIVE` is rejected.

```sql
WITH active AS (
    SELECT id, name FROM customer WHERE open = TRUE
)
SELECT name FROM active ORDER BY name;
```

A view still stores one base relation. It can use `GROUP BY` and `HAVING` on that table. A view with `JOIN`, `UNION`, or `WITH` fails. `SELECT INTO` stays on one table. A cursor and `FOR SELECT` accept the same query clauses, except `LIMIT` and `OFFSET`.

Outside the dialect: subquery, `NATURAL JOIN`, `USING`, a comma join, `INSERT ... SELECT`, `WITH RECURSIVE`, and `SAVEPOINT`.

## ResultSet

The result is read-only and fits in memory. You can move with `next`, `previous`, `first`, `last`, `absolute`, and `relative`. `CONCUR_UPDATABLE` and `TYPE_SCROLL_SENSITIVE` are rejected.

A column is read by name or by index, starting at 1. `wasNull()` applies after a null read. `Statement.setMaxRows` limits how many rows go into the result.

```java
try (ResultSet rows = statement.executeQuery(
        "SELECT id, name FROM customer ORDER BY id")) {
    rows.last();
    int total = rows.getRow();
    rows.beforeFirst();
    while (rows.next()) {
        long id = rows.getLong(1);
        String name = rows.getString("name");
    }
}
```

`ResultSetMetaData` exposes the name, the SQL type, `Types`, the size, the scale, nullability, and autoincrement.

## Transaction

With auto-commit on, each command is committed on its own. Turning auto-commit off opens a transaction on the next statement, including `CREATE`, `DROP`, and `ALTER`. `commit()` keeps that work. `rollback()` undoes it. Turning auto-commit back to `true` commits this connection's transaction. Closing the connection with an open transaction rolls it back.

```java
connection.setAutoCommit(false);
try (PreparedStatement update = connection.prepareStatement(
        "UPDATE customer SET name = ? WHERE id = ?")) {
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

The same effect through SQL, still with auto-commit on:

```sql
BEGIN;
UPDATE customer SET name = 'Ana Silva' WHERE id = 1;
COMMIT;
```

`ROLLBACK` in place of `COMMIT` discards the work, including `CREATE`, `DROP`, and `ALTER`. `START TRANSACTION` also opens a transaction. `BEGIN` and `COMMIT` accept `WORK` or `TRANSACTION` after them.

`setReadOnly(true)` allows `SELECT` and the transaction commands. Everything else fails with SQLSTATE `25006`.

## Multiple connections

In the same process, several `jdbc:nexora:` connections to the same file share the database. Each connection supplies a user and a password. The `roleName` property (or `role`) chooses the role; empty or `NONE` signs in with no role. Another process receives a database-already-open error.

`DROP CONNECTION n` drops the active connection numbered `n`, the same number as `CURRENT_CONNECTION`. The DBA drops any of them. Another user drops only their own. The dropped connection leaves `NEX$CONNECTIONS`, and the next command on it fails. If it owned the open transaction, the database rolls that transaction back.

Only one transaction stays open. While it exists, the other connections can read, including what has not been committed yet, and they do not write. A write attempt fails with the open-transaction message.

`setTransactionIsolation` accepts `READ_UNCOMMITTED`, `READ_COMMITTED`, `REPEATABLE_READ`, and `SERIALIZABLE`. The value is recorded on the connection. Visibility among connections in the same process remains as described above. `TRANSACTION_NONE` is rejected.

## Batch

```java
try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO customer (name, email) VALUES (?, ?)")) {
    insert.setString(1, "Bia");
    insert.setString(2, "bia@example.com");
    insert.addBatch();
    insert.setString(1, "Caio");
    insert.setString(2, "caio@example.com");
    insert.addBatch();
    int[] counts = insert.executeBatch();
}
```

On a `Statement`, `addBatch` receives the SQL and `executeBatch` runs each command. A `SELECT` in the batch fails. If a command in the middle fails, the exception is `BatchUpdateException` and the array carries the counts already obtained.

## Metadata

The catalog is `null`. The default schema is `public`. A name pattern uses `%` and `_`, with no case distinction: `"PUBLIC"` matches `public`. `NEX$` tables appear as `SYSTEM TABLE`. `TABLE` returns only the tables created by the application.

```java
DatabaseMetaData meta = connection.getMetaData();
meta.getDatabaseProductName();   // Nexora
meta.getDriverName();            // Nexora JDBC

try (ResultSet tables = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
    while (tables.next()) {
        String table = tables.getString("TABLE_NAME");
    }
}

try (ResultSet columns = meta.getColumns(null, null, "customer", "%")) {
    while (columns.next()) {
        String name = columns.getString("COLUMN_NAME");
        String type = columns.getString("TYPE_NAME");
        int size = columns.getInt("COLUMN_SIZE");
        String nullable = columns.getString("IS_NULLABLE");
        String auto = columns.getString("IS_AUTOINCREMENT");
    }
}

try (ResultSet keys = meta.getPrimaryKeys(null, null, "customer")) {
    while (keys.next()) {
        String column = keys.getString("COLUMN_NAME");
        short position = keys.getShort("KEY_SEQ");
        String name = keys.getString("PK_NAME"); // PK_customer
    }
}

try (ResultSet indexes = meta.getIndexInfo(null, null, "customer", false, false)) {
    while (indexes.next()) {
        String name = indexes.getString("INDEX_NAME");
        boolean nonUnique = indexes.getBoolean("NON_UNIQUE");
        String column = indexes.getString("COLUMN_NAME");
        short position = indexes.getShort("ORDINAL_POSITION");
    }
}
```

`getIndexInfo(..., true, ...)` returns only unique indexes, among them `PRIMARY` and each `UNIQUE`. `getTypeInfo()` lists the types from the table above. `TABLE_SCHEM` carries each table's schema. `PROCEDURE_SCHEM` carries each procedure's schema. `getSchemas()` lists the schemas, starting with `public`. A schema that does not exist, such as `"other"`, leaves the result empty.

## Errors

An SQL failure becomes an `SQLException`. A syntax message or a database-rule message uses SQLSTATE `42000`. A closed connection uses `08003`. `commit` or `rollback` with auto-commit on uses `25000`. A URL with no file uses `08001`. A blank user or password uses `28000`. A feature outside the driver, such as `CallableStatement`, `CLOB`, `ARRAY`, or a savepoint, throws `SQLFeatureNotSupportedException`. A procedure runs through SQL, with `EXECUTE PROCEDURE`.

## File and internal tables

The database is the `.db` file: page 0 is the header, the catalog comes next, and each table occupies whole pages, with the `BLOB` stored in the table itself. Beside it is the `.log`, with the same name and a different extension (`store.db` and `store.log`). Each committed transaction is stored in that log, compressed, and commit writes the log before the pages. The checkpoint synchronizes the `.db` and keeps that history, from creation or the last full backup. The incremental backup copies only the `.log`. If the process crashes in the middle, the next open reapplies what remained after the checkpoint. `apply` decompresses the transactions and runs the SQL again against a `.db`. There is no folder of tables. The page size is the one chosen in `create`. `NEX$FORMAT` remains `1`.

Thirteen tables are created with the file, among them `NEX$SCHEMAS` with the `public` row. `CREATE TABLE` adds the owner in `NEX$PRIVILEGES`. The `NEX$` prefix is reserved. `DROP`, `ALTER`, and `CREATE INDEX` on them fail. Identifiers may use `$`.


| Table                      | Purpose                                                             | Writes                          |
| -------------------------- | ------------------------------------------------------------------- | ------------------------------- |
| `NEX$DATABASE`             | name, description, owner, date, file version, and `NEX$PAGE_SIZE`  | read-only                       |
| `NEX$CONFIG`               | key and value (`Auth.Hash`, `Default.CharacterSet`)                 | DBA                             |
| `NEX$USERS`                | users, salted password, active, and administrator                   | DBA                             |
| `NEX$PRIVILEGES`           | what each user can do on each table                                 | `GRANT` and `REVOKE`            |
| `NEX$CONNECTIONS`          | active connections: user, login, state, and remote origin           | the database itself             |
| `NEX$GENERATORS`           | schema, name, current value, start, step, and generator owner       | `CREATE` / `SET GENERATOR`      |
| `NEX$PROCEDURES`           | schema, name, code, and procedure owner                             | `CREATE PROCEDURE`              |
| `NEX$PROCEDURE_PARAMETERS` | schema, arguments, and return values (`NEX$PARAMETER_TYPE` 0 or 1) | `CREATE PROCEDURE`              |
| `NEX$TRIGGERS`             | name, table, position, phase, code, inactive, and owner             | `CREATE TRIGGER`                |
| `NEX$VIEWS`                | name, `SELECT`, base relation, columns, and owner                   | `CREATE VIEW`                   |
| `NEX$ROLES`                | role name and owner                                                 | `CREATE ROLE`                   |
| `NEX$ROLE_MEMBERS`         | who was granted the role and whether they can grant it further      | `GRANT role TO user`            |
| `NEX$SCHEMAS`              | name and owner of each schema; `public` is the default              | `CREATE SCHEMA` / `DROP SCHEMA` |


The initial DBA is `DBA`, password `dba`, administrator and active. Another user is added with `INSERT` into `NEX$USERS`; the `NEX$PASSWORD` column receives the password as text and the database stores bcrypt. `SELECT` returns the hash, not the password. The last DBA cannot be deleted or deactivated.

```sql
INSERT INTO NEX$USERS (NEX$USER_NAME, NEX$PASSWORD) VALUES ('ana', 'ana');
UPDATE NEX$USERS SET NEX$PASSWORD = 'new' WHERE NEX$USER_NAME = 'DBA';
SELECT NEX$USER_NAME, NEX$REMOTE_PROTOCOL, NEX$REMOTE_ADDRESS, NEX$CONNECTED_AT FROM NEX$CONNECTIONS;
```

The row exists only while the connection is open: `NEX$STATE` is `ACTIVE`. On close, the row is deleted. If the process crashes, the next open deletes the rows that remained. `NEX$REMOTE_PROTOCOL` is `local` on the embedded connection and `TCPv4` or `TCPv6` on the server. `NEX$REMOTE_ADDRESS` is the client address. `NEX$REMOTE_HOST`, `NEX$REMOTE_OS_USER`, and `NEX$REMOTE_PROCESS` come from the client process.

## Authorization

The DBA is allowed on every table. The user who creates a table becomes its owner and can read, write, alter the structure, and grant access. Other users get in only through `GRANT`.

`NEX$PRIVILEGES` stores the user, the table, the privilege (`OWNER`, `SELECT`, `INSERT`, `UPDATE`, or `DELETE`), whether it can be granted further (`NEX$GRANT_OPTION`), and who granted it (`NEX$GRANTOR`). A direct `INSERT` into that table fails. `PUBLIC` applies to everyone.

```sql
GRANT SELECT ON customer TO ana;
GRANT SELECT, INSERT ON customer TO ana WITH GRANT OPTION;
GRANT ALL ON customer TO PUBLIC;
REVOKE INSERT ON customer FROM ana;
REVOKE ALL ON customer FROM PUBLIC;
```

`ALL` covers `SELECT`, `INSERT`, `UPDATE`, and `DELETE`. It does not take away ownership. `GRANT` and `REVOKE` do not run in the middle of a transaction.

A role receives a table privilege and is granted to a user. At login, only the supplied role applies; with no role, `CURRENT_ROLE` is `NONE` and the role privilege does not apply. The DBA can use any existing role. The user who creates the role is its owner and can grant it. `WITH ADMIN OPTION` lets that user grant the role further. `PUBLIC` can receive the role as well. The role name cannot match a user, and it cannot be `NONE` or `PUBLIC`.

```sql
CREATE ROLE sales;
GRANT SELECT ON customer TO sales;
GRANT sales TO ana WITH ADMIN OPTION;
REVOKE sales FROM ana;
DROP ROLE sales;
```

```java
Session ana = db.login("ana", "ana", "sales");
```



## Generators, procedures, and triggers

The value is committed immediately: `ROLLBACK` undoes the row, but not the `GEN_ID`. `CREATE GENERATOR` starts at 0, so the first `GEN_ID(name, 1)` returns 1. `SET GENERATOR name TO 100` stores 100; the next step of 1 returns 101. `CREATE SEQUENCE name START WITH 10 INCREMENT BY 2` makes the first `NEXT VALUE FOR name` return 10. `ALTER SEQUENCE name RESTART WITH 20` makes the next value 20. `GEN_ID(name, 0)` only reads the current value. The user who creates the generator is its owner; the DBA can drop it as well.

A generator and a sequence live in the schema, like a table and a procedure. Without a prefix, the name uses the session schema. `CREATE SEQUENCE sales.seq_item` stores it in `sales`, and the same name can exist in another schema. `GEN_ID` and `NEXT VALUE FOR` without a schema use the session; inside a procedure or trigger, they use the routine's schema or the table's schema. `DROP SCHEMA ... CASCADE` drops the sequences of that schema. `NEX$SCHEMA_NAME` stores the schema, including `public`.

```sql
CREATE GENERATOR gen_item;
CREATE SEQUENCE seq_item START WITH 10 INCREMENT BY 2;
SELECT GEN_ID(gen_item, 1) AS n FROM NEX$DATABASE;
SELECT NEXT VALUE FOR seq_item AS n FROM NEX$DATABASE;
SET GENERATOR gen_item TO 100;
ALTER SEQUENCE seq_item RESTART WITH 20;
DROP GENERATOR gen_item;
```

A procedure stores the code and the parameters, in the session schema or in the schema written in the name. `CREATE PROCEDURE sales.series` lives in `sales`; without the prefix, it lives in the current schema. The same name can exist in different schemas. `EXECUTE PROCEDURE series` and `SELECT ... FROM series(...)` use the session schema when the name carries no schema. In the body, a name without a schema uses the procedure's schema. `DROP SCHEMA ... CASCADE` drops the procedures of that schema. `NEX$SCHEMA_NAME` stores the schema, including `public`.

In the body, the semicolon is part of the routine: `SET TERM` is not required. `EXECUTE PROCEDURE` returns one row with the return values, stopping at the first `SUSPEND`. `SELECT` from `name(arguments)` returns one row per `SUSPEND`. Without `SUSPEND`, the `SELECT` comes back empty and `EXECUTE PROCEDURE` still returns the final values. Inside the routine SQL, a variable uses `:name`. In `IF`, `WHILE`, and assignment, the bare name is already the variable. `NEW` and `OLD` exist in a trigger. `OLD` is only read. An assignment in `BEFORE INSERT` or `BEFORE UPDATE` goes into the row; in `AFTER`, it does not change what was already written. `DELETE` has only `OLD`.

```sql
CREATE PROCEDURE series (up_to INTEGER)
RETURNS (n INTEGER)
AS
BEGIN
  n = 1;
  WHILE (n <= up_to) DO
  BEGIN
    SUSPEND;
    n = n + 1;
  END
END

SELECT n FROM series(3);

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

The body accepts `DECLARE VARIABLE`, `DECLARE cursor CURSOR FOR (SELECT ...)`, `OPEN`, `FETCH ... INTO`, `CLOSE`, `FOR SELECT ... INTO ... DO`, `BEGIN/END`, assignment, `IF/THEN/ELSE`, `WHILE/DO`, `EXIT`, `SUSPEND`, `EXCEPTION 'text'`, `INSERT`, `UPDATE`, `DELETE`, `EXECUTE PROCEDURE`, `SELECT ... INTO`, and `RETURNING_VALUES`. `ROW_COUNT` is 1 after a `FETCH` that got a row and 0 when the cursor is exhausted; it also receives the row count of the last `INSERT`, `UPDATE`, `DELETE`, or `SELECT INTO` in the routine. The cursor `SELECT` sees `:variable` and `NEW`/`OLD` at `OPEN` time. A `FETCH` with no row does not change the variables. Opening an open cursor, or reading or closing a closed cursor, fails. A lower position runs first. An inactive trigger does not run. `DROP TABLE` drops the triggers of the table. Chained procedures and triggers stop at 32 calls. The owner or the DBA alters the object; a trigger on a table also requires being that table's owner or the DBA. A direct `INSERT` into these catalog tables fails.

```sql
CREATE PROCEDURE starting_from (minimum INTEGER)
RETURNS (id INTEGER, name VARCHAR(20))
AS
DECLARE c CURSOR FOR (SELECT id, name FROM item WHERE id >= :minimum ORDER BY id);
BEGIN
  OPEN c;
  FETCH c INTO id, name;
  WHILE (ROW_COUNT > 0) DO
  BEGIN
    SUSPEND;
    FETCH c INTO id, name;
  END
  CLOSE c;
END

FOR SELECT name FROM item ORDER BY id
INTO name
DO SUSPEND;
```

A view is a stored `SELECT`, from one table or from another view. `SELECT` on it applies `WHERE`, a column list, `DISTINCT`, `ORDER BY`, and `LIMIT` on top of the result. `INSERT`, `UPDATE`, and `DELETE` fail: the view is read-only. The name cannot match a table. `DROP TABLE` or `DROP VIEW` fails while another view depends on the object. The owner or the DBA removes the view. `GRANT` and `REVOKE` use its name, with `ON` or `ON VIEW`. `SELECT *` uses the columns stored at creation. JDBC lists the type `VIEW`.

```sql
CREATE VIEW active (person) AS
SELECT name FROM item WHERE active = TRUE;

SELECT person FROM active WHERE person <> 'Caio' ORDER BY person;
SELECT COUNT(*) FROM active;

CREATE VIEW names AS SELECT person FROM active;
DROP VIEW names;
DROP VIEW active;
```
