package io.github.gabrielmmoraes1999.nexora;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SqlParser {

    private final String sql;
    private final List<Token> tokens = new ArrayList<>();
    private int cursor;
    private int parameters;

    private SqlParser(String sql) {
        this.sql = sql;
    }

    static ParsedSql parse(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new NexException("SQL vazio");
        }
        SqlParser parser = new SqlParser(sql);
        parser.lex();
        return new ParsedSql(parser.script(), parser.parameters);
    }

    static Select parseView(String source) {
        if (source == null || source.isBlank()) {
            throw new NexException("view sem SELECT");
        }
        SqlParser parser = new SqlParser(source);
        parser.lex();
        if (!parser.peekKeyword("SELECT")) {
            throw new NexException("view espera SELECT");
        }
        parser.expectKeyword("SELECT");
        Stmt body = parser.select();
        if (parser.parameters != 0) {
            throw new NexException("view não aceita parâmetros");
        }
        parser.skipSemicolons();
        if (!parser.peek(TokenKind.EOF)) {
            throw new NexException("texto após o SELECT da view");
        }
        if (!(body instanceof Select select)) {
            throw new NexException("view não aceita SELECT de procedure");
        }
        return select;
    }

    static Routine parseRoutine(String source) {
        if (source == null || source.isBlank()) {
            throw new NexException("rotina sem corpo");
        }
        SqlParser parser = new SqlParser(source);
        parser.lex();
        Routine routine = parser.routineBody();
        parser.skipSemicolons();
        if (!parser.peek(TokenKind.EOF)) {
            throw parser.error("texto após o END da rotina");
        }
        return routine;
    }

    private List<Stmt> script() {
        List<Stmt> statements = new ArrayList<>();
        skipSemicolons();
        if (peek(TokenKind.EOF)) {
            throw new NexException("SQL vazio");
        }
        while (!peek(TokenKind.EOF)) {
            statements.add(statement());
            if (consumeSymbol(";")) {
                skipSemicolons();
                continue;
            }
            if (!peek(TokenKind.EOF)) {
                throw error("faltou ';' entre os comandos");
            }
        }
        return statements;
    }

    private void skipSemicolons() {
        while (consumeSymbol(";")) {
            // comandos vazios são ignorados
        }
    }

    private Stmt statement() {
        if (consumeKeyword("CREATE")) {
            return create();
        }
        if (consumeKeyword("DROP")) {
            return drop();
        }
        if (consumeKeyword("ALTER")) {
            return alter();
        }
        if (consumeKeyword("INSERT")) {
            return insert();
        }
        if (consumeKeyword("UPDATE")) {
            return update();
        }
        if (consumeKeyword("DELETE")) {
            return delete();
        }
        if (consumeKeyword("GRANT")) {
            return grant();
        }
        if (consumeKeyword("REVOKE")) {
            return revoke();
        }
        if (consumeKeyword("SET")) {
            if (consumeKeyword("SCHEMA")) {
                return new SetSchema(expectIdent());
            }
            return setGenerator();
        }
        if (consumeKeyword("EXECUTE")) {
            return executeProcedure(false);
        }
        if (consumeKeyword("SELECT")) {
            return select();
        }
        if (consumeKeyword("BEGIN")) {
            optionalKeyword("TRANSACTION");
            optionalKeyword("WORK");
            return new BeginTx();
        }
        if (consumeKeyword("START")) {
            expectKeyword("TRANSACTION");
            return new BeginTx();
        }
        if (consumeKeyword("COMMIT")) {
            optionalKeyword("TRANSACTION");
            optionalKeyword("WORK");
            return new CommitTx();
        }
        if (consumeKeyword("ROLLBACK")) {
            optionalKeyword("TRANSACTION");
            optionalKeyword("WORK");
            return new RollbackTx();
        }
        throw error("comando SQL não reconhecido");
    }

    private Stmt grant() {
        if (!peekPrivilege()) {
            String role = expectIdent();
            expectKeyword("TO");
            String user = expectIdent();
            boolean adminOption = false;
            if (consumeKeyword("WITH")) {
                expectKeyword("ADMIN");
                expectKeyword("OPTION");
                adminOption = true;
            }
            return new GrantRole(role, user, adminOption);
        }
        List<String> privileges = privilegeList();
        expectKeyword("ON");
        if (!consumeKeyword("TABLE")) {
            consumeKeyword("VIEW");
        }
        String table = expectRelation();
        expectKeyword("TO");
        String user = expectIdent();
        boolean grantOption = false;
        if (consumeKeyword("WITH")) {
            expectKeyword("GRANT");
            expectKeyword("OPTION");
            grantOption = true;
        }
        return new Grant(privileges, table, user, grantOption);
    }

    private Stmt revoke() {
        if (consumeKeyword("ADMIN")) {
            expectKeyword("OPTION");
            expectKeyword("FOR");
            String role = expectIdent();
            expectKeyword("FROM");
            return new RevokeRole(role, expectIdent(), true);
        }
        if (!peekPrivilege()) {
            String role = expectIdent();
            expectKeyword("FROM");
            return new RevokeRole(role, expectIdent(), false);
        }
        List<String> privileges = privilegeList();
        expectKeyword("ON");
        if (!consumeKeyword("TABLE")) {
            consumeKeyword("VIEW");
        }
        String table = expectRelation();
        expectKeyword("FROM");
        String user = expectIdent();
        return new Revoke(privileges, table, user);
    }

    private List<String> privilegeList() {
        if (consumeKeyword("ALL")) {
            optionalKeyword("PRIVILEGES");
            return new ArrayList<>(SystemTables.DML);
        }
        List<String> privileges = new ArrayList<>();
        do {
            String privilege = privilegeName();
            if (privileges.stream().anyMatch(item -> item.equals(privilege))) {
                throw error("privilégio repetido: " + privilege);
            }
            privileges.add(privilege);
        } while (consumeSymbol(","));
        return privileges;
    }

    private boolean peekPrivilege() {
        return peekKeyword("SELECT") || peekKeyword("INSERT") || peekKeyword("UPDATE")
                || peekKeyword("DELETE") || peekKeyword("ALL");
    }

    private String privilegeName() {
        if (consumeKeyword("SELECT")) {
            return "SELECT";
        }
        if (consumeKeyword("INSERT")) {
            return "INSERT";
        }
        if (consumeKeyword("UPDATE")) {
            return "UPDATE";
        }
        if (consumeKeyword("DELETE")) {
            return "DELETE";
        }
        throw error("privilégio esperado: SELECT, INSERT, UPDATE, DELETE ou ALL");
    }

    private Stmt create() {
        if (consumeKeyword("GENERATOR")) {
            return createGenerator(false);
        }
        if (consumeKeyword("SEQUENCE")) {
            return createGenerator(true);
        }
        if (consumeKeyword("PROCEDURE")) {
            return createProcedure();
        }
        if (consumeKeyword("TRIGGER")) {
            return createTrigger();
        }
        if (consumeKeyword("VIEW")) {
            return createView();
        }
        if (consumeKeyword("ROLE")) {
            return new CreateRole(ifNotExists(), expectIdent());
        }
        if (consumeKeyword("SCHEMA")) {
            return new CreateSchema(ifNotExists(), expectIdent());
        }
        if (consumeKeyword("UNIQUE")) {
            expectKeyword("INDEX");
            return createIndex(true);
        }
        if (consumeKeyword("INDEX")) {
            return createIndex(false);
        }
        expectKeyword("TABLE");
        boolean ifNotExists = false;
        if (consumeKeyword("IF")) {
            expectKeyword("NOT");
            expectKeyword("EXISTS");
            ifNotExists = true;
        }
        String name = expectRelation();
        expectSymbol("(");
        List<ColDef> columns = new ArrayList<>();
        List<String> primary = new ArrayList<>();
        List<List<String>> uniques = new ArrayList<>();
        TableKeys keys = new TableKeys();
        if (!peekSymbol(")")) {
            do {
                if (peekKeyword("CONSTRAINT") || (peekKeyword("PRIMARY") && peekKeyword(1, "KEY"))
                        || (peekKeyword("FOREIGN") && peekKeyword(1, "KEY"))
                        || (peekKeyword("UNIQUE") && peekSymbol(1, "("))) {
                    tableConstraint(primary, uniques, keys);
                } else {
                    columns.add(column());
                }
            } while (consumeSymbol(","));
        }
        expectSymbol(")");
        if (columns.isEmpty()) {
            throw error("CREATE TABLE precisa de ao menos uma coluna");
        }
        return new CreateTable(ifNotExists, name, columns, primary, uniques,
                keys.primaryConstraint, keys.primaryIndex, keys.primaryDescending, List.copyOf(keys.foreignKeys));
    }

    private Stmt createView() {
        boolean ifNotExists = false;
        if (consumeKeyword("IF")) {
            expectKeyword("NOT");
            expectKeyword("EXISTS");
            ifNotExists = true;
        }
        String name = expectRelation();
        List<String> columns = peekSymbol("(") ? columnList() : List.of();
        expectKeyword("AS");
        if (!peekKeyword("SELECT")) {
            throw error("CREATE VIEW espera SELECT");
        }
        int start = current().start;
        int before = parameters;
        expectKeyword("SELECT");
        Stmt body = select();
        if (parameters != before) {
            throw error("CREATE VIEW não aceita parâmetros");
        }
        if (!(body instanceof Select)) {
            throw error("CREATE VIEW não aceita SELECT de procedure");
        }
        int end = tokens.get(cursor - 1).end;
        return new CreateView(ifNotExists, name, columns, sql.substring(start, end).trim());
    }

    private Stmt createIndex(boolean unique) {
        boolean ifNotExists = false;
        if (consumeKeyword("IF")) {
            expectKeyword("NOT");
            expectKeyword("EXISTS");
            ifNotExists = true;
        }
        String name = expectIdent();
        expectKeyword("ON");
        String table = expectRelation();
        return new CreateIndex(unique, ifNotExists, name, table, columnList());
    }

    private void tableConstraint(List<String> primary, List<List<String>> uniques, TableKeys keys) {
        String name = null;
        if (consumeKeyword("CONSTRAINT")) {
            name = expectIdent();
        }
        if (consumeKeyword("PRIMARY")) {
            expectKeyword("KEY");
            if (!primary.isEmpty()) {
                throw error("a tabela só pode ter uma chave primária");
            }
            primary.addAll(columnList());
            Using using = usingIndex();
            keys.primaryConstraint = name;
            keys.primaryIndex = using.name;
            keys.primaryDescending = using.descending;
            return;
        }
        if (consumeKeyword("FOREIGN")) {
            expectKeyword("KEY");
            List<String> columns = columnList();
            expectKeyword("REFERENCES");
            String refTable = expectRelation();
            List<String> refColumns = peekSymbol("(") ? columnList() : List.of();
            Actions actions = referentialActions();
            Using using = usingIndex();
            keys.foreignKeys.add(new ForeignClause(name, columns, using.name, using.descending, refTable, refColumns,
                    actions.onUpdate, actions.onDelete));
            return;
        }
        expectKeyword("UNIQUE");
        uniques.add(columnList());
        if (peekKeyword("USING")) {
            throw error("USING INDEX vale para PRIMARY KEY e FOREIGN KEY");
        }
    }

    private Using usingIndex() {
        if (!consumeKeyword("USING")) {
            return new Using(null, false);
        }
        boolean descending = false;
        if (consumeKeyword("DESCENDING") || consumeKeyword("DESC")) {
            descending = true;
        } else if (consumeKeyword("ASCENDING") || consumeKeyword("ASC")) {
            descending = false;
        }
        expectKeyword("INDEX");
        return new Using(expectIdent(), descending);
    }

    private Actions referentialActions() {
        ReferentialAction onDelete = ReferentialAction.NO_ACTION;
        ReferentialAction onUpdate = ReferentialAction.NO_ACTION;
        boolean sawDelete = false;
        boolean sawUpdate = false;
        while (peekKeyword("ON")) {
            consumeKeyword("ON");
            boolean delete;
            if (consumeKeyword("DELETE")) {
                delete = true;
            } else if (consumeKeyword("UPDATE")) {
                delete = false;
            } else {
                throw error("ON espera DELETE ou UPDATE");
            }
            if (delete && sawDelete) {
                throw error("ON DELETE repetido");
            }
            if (!delete && sawUpdate) {
                throw error("ON UPDATE repetido");
            }
            ReferentialAction action = referentialAction();
            if (delete) {
                onDelete = action;
                sawDelete = true;
            } else {
                onUpdate = action;
                sawUpdate = true;
            }
        }
        return new Actions(onUpdate, onDelete);
    }

    private ReferentialAction referentialAction() {
        if (consumeKeyword("CASCADE")) {
            return ReferentialAction.CASCADE;
        }
        if (consumeKeyword("RESTRICT")) {
            return ReferentialAction.RESTRICT;
        }
        if (consumeKeyword("SET")) {
            if (consumeKeyword("NULL")) {
                return ReferentialAction.SET_NULL;
            }
            expectKeyword("DEFAULT");
            return ReferentialAction.SET_DEFAULT;
        }
        if (consumeKeyword("NO")) {
            expectKeyword("ACTION");
            return ReferentialAction.NO_ACTION;
        }
        throw error("ação referencial espera NO ACTION, RESTRICT, CASCADE, SET NULL ou SET DEFAULT");
    }

    private record Actions(ReferentialAction onUpdate, ReferentialAction onDelete) {
    }

    private static final class TableKeys {
        private String primaryConstraint;
        private String primaryIndex;
        private boolean primaryDescending;
        private final List<ForeignClause> foreignKeys = new ArrayList<>();
    }

    private record Using(String name, boolean descending) {
    }

    private Stmt drop() {
        if (consumeKeyword("GENERATOR") || consumeKeyword("SEQUENCE")) {
            return new DropGenerator(ifExists(), expectIdent());
        }
        if (consumeKeyword("PROCEDURE")) {
            return new DropProcedure(ifExists(), expectIdent());
        }
        if (consumeKeyword("TRIGGER")) {
            return new DropTrigger(ifExists(), expectIdent());
        }
        if (consumeKeyword("VIEW")) {
            return new DropView(ifExists(), expectRelation());
        }
        if (consumeKeyword("ROLE")) {
            return new DropRole(ifExists(), expectIdent());
        }
        if (consumeKeyword("SCHEMA")) {
            boolean ifExists = ifExists();
            String name = expectIdent();
            boolean cascade = consumeKeyword("CASCADE");
            if (!cascade) {
                consumeKeyword("RESTRICT");
            }
            return new DropSchema(ifExists, name, cascade);
        }
        if (consumeKeyword("CONNECTION")) {
            return new DropConnection(expression());
        }
        if (consumeKeyword("INDEX")) {
            boolean ifExists = false;
            if (consumeKeyword("IF")) {
                expectKeyword("EXISTS");
                ifExists = true;
            }
            String name = expectIdent();
            expectKeyword("ON");
            return new DropIndex(ifExists, name, expectRelation());
        }
        if (!peekKeyword("TABLE")) {
            throw error("DROP suporta TABLE, INDEX, SCHEMA, GENERATOR, SEQUENCE, PROCEDURE, TRIGGER, VIEW, ROLE e CONNECTION");
        }
        expectKeyword("TABLE");
        boolean ifExists = false;
        if (consumeKeyword("IF")) {
            expectKeyword("EXISTS");
            ifExists = true;
        }
        return new DropTable(ifExists, expectRelation());
    }

    private Stmt alter() {
        if (consumeKeyword("TRIGGER")) {
            String name = expectIdent();
            if (consumeKeyword("ACTIVE")) {
                return new AlterTrigger(name, false);
            }
            if (consumeKeyword("INACTIVE")) {
                return new AlterTrigger(name, true);
            }
            throw error("ALTER TRIGGER aceita ACTIVE ou INACTIVE");
        }
        if (consumeKeyword("SEQUENCE") || consumeKeyword("GENERATOR")) {
            String name = expectIdent();
            expectKeyword("RESTART");
            expectKeyword("WITH");
            return new SetGenerator(name, expression(), true);
        }
        expectKeyword("TABLE");
        String table = expectRelation();
        if (consumeKeyword("ADD")) {
            if (peekKeyword("CONSTRAINT") || peekKeyword("PRIMARY") || peekKeyword("FOREIGN")) {
                return addTableConstraint(table);
            }
            optionalKeyword("COLUMN");
            return new AddColumn(table, column());
        }
        if (consumeKeyword("DROP")) {
            optionalKeyword("COLUMN");
            return new DropColumn(table, expectIdent());
        }
        throw error("ALTER TABLE suporta ADD COLUMN, DROP COLUMN, ADD PRIMARY KEY e ADD FOREIGN KEY");
    }

    private Stmt addTableConstraint(String table) {
        String name = null;
        if (consumeKeyword("CONSTRAINT")) {
            name = expectIdent();
        }
        if (consumeKeyword("PRIMARY")) {
            expectKeyword("KEY");
            List<String> columns = columnList();
            Using using = usingIndex();
            return new AddPrimaryKey(table, columns, name, using.name, using.descending);
        }
        if (consumeKeyword("FOREIGN")) {
            expectKeyword("KEY");
            List<String> columns = columnList();
            expectKeyword("REFERENCES");
            String refTable = expectRelation();
            List<String> refColumns = peekSymbol("(") ? columnList() : List.of();
            Actions actions = referentialActions();
            Using using = usingIndex();
            return new AddForeignKey(table, columns, name, using.name, using.descending, refTable, refColumns,
                    actions.onUpdate, actions.onDelete);
        }
        throw error("ADD CONSTRAINT espera PRIMARY KEY ou FOREIGN KEY");
    }

    private Stmt setGenerator() {
        expectKeyword("GENERATOR");
        String name = expectIdent();
        expectKeyword("TO");
        return new SetGenerator(name, expression(), false);
    }

    private Stmt createGenerator(boolean sequence) {
        String name = expectIdent();
        Long start = null;
        Long increment = null;
        if (consumeKeyword("START")) {
            expectKeyword("WITH");
            start = signedLong("START WITH");
        }
        if (consumeKeyword("INCREMENT")) {
            expectKeyword("BY");
            increment = signedLong("INCREMENT BY");
            if (increment == 0) {
                throw error("INCREMENT BY não pode ser zero");
            }
        }
        return new CreateGenerator(name, start, increment, sequence);
    }

    private Stmt createProcedure() {
        String name = expectIdent();
        List<ParamDef> inputs = parameterList(false);
        List<ParamDef> outputs = List.of();
        if (consumeKeyword("RETURNS")) {
            outputs = parameterList(true);
        }
        expectKeyword("AS");
        String source = captureRoutine();
        parseRoutine(source);
        return new CreateProcedure(name, inputs, outputs, source);
    }

    private Stmt createTrigger() {
        String name = expectIdent();
        expectKeyword("FOR");
        String table = expectRelation();
        boolean inactive = false;
        if (consumeKeyword("INACTIVE")) {
            inactive = true;
        } else {
            consumeKeyword("ACTIVE");
        }
        String phase;
        if (consumeKeyword("BEFORE")) {
            phase = "BEFORE";
        } else {
            expectKeyword("AFTER");
            phase = "AFTER";
        }
        List<String> events = new ArrayList<>();
        do {
            String event = eventName();
            if (events.stream().anyMatch(item -> item.equals(event))) {
                throw error("evento repetido: " + event);
            }
            events.add(event);
        } while (consumeKeyword("OR"));
        int position = 0;
        if (consumeKeyword("POSITION")) {
            long raw = signedLong("POSITION");
            if (raw > Integer.MAX_VALUE || raw < Integer.MIN_VALUE) {
                throw error("POSITION grande demais");
            }
            position = (int) raw;
        }
        expectKeyword("AS");
        String source = captureRoutine();
        parseRoutine(source);
        return new CreateTrigger(name, table, inactive, phase, events, position, source);
    }

    private String eventName() {
        if (consumeKeyword("INSERT")) {
            return "INSERT";
        }
        if (consumeKeyword("UPDATE")) {
            return "UPDATE";
        }
        if (consumeKeyword("DELETE")) {
            return "DELETE";
        }
        throw error("evento esperado: INSERT, UPDATE ou DELETE");
    }

    private List<ParamDef> parameterList(boolean required) {
        if (!required && !peekSymbol("(")) {
            return List.of();
        }
        expectSymbol("(");
        List<ParamDef> parameters = new ArrayList<>();
        if (!peekSymbol(")")) {
            do {
                parameters.add(parameter());
            } while (consumeSymbol(","));
        }
        expectSymbol(")");
        return parameters;
    }

    private ParamDef parameter() {
        String name = expectIdent();
        TypeSpec type = parseType();
        return new ParamDef(name, type.type, type.length, type.scale);
    }

    private Stmt executeProcedure(boolean insideRoutine) {
        expectKeyword("PROCEDURE");
        String name = expectIdent();
        List<Expr> arguments = new ArrayList<>();
        if (consumeSymbol("(")) {
            if (!peekSymbol(")")) {
                do {
                    arguments.add(expression());
                } while (consumeSymbol(","));
            }
            expectSymbol(")");
        }
        List<String> returning = new ArrayList<>();
        if (consumeKeyword("RETURNING_VALUES")) {
            if (!insideRoutine) {
                throw error("RETURNING_VALUES só pode ser usado dentro de procedure ou trigger");
            }
            do {
                if (consumeSymbol(":")) {
                    returning.add(expectIdent());
                } else {
                    returning.add(expectIdent());
                }
            } while (consumeSymbol(","));
        }
        return new ExecuteProcedure(name, arguments, returning);
    }

    private String captureRoutine() {
        int start = current().start;
        routineBody();
        int end = tokens.get(cursor - 1).end;
        return sql.substring(start, end);
    }

    private Routine routineBody() {
        List<ParamDef> locals = new ArrayList<>();
        List<CursorDef> cursors = new ArrayList<>();
        while (consumeKeyword("DECLARE")) {
            if (consumeKeyword("VARIABLE")) {
                locals.add(parameter());
                expectSymbol(";");
                continue;
            }
            String name = expectIdent();
            expectKeyword("CURSOR");
            expectKeyword("FOR");
            expectSymbol("(");
            Select query = cursorQuery();
            expectSymbol(")");
            expectSymbol(";");
            cursors.add(new CursorDef(name, query));
        }
        return new Routine(locals, cursors, block());
    }

    private Psql block() {
        expectKeyword("BEGIN");
        List<Psql> statements = new ArrayList<>();
        while (consumeSymbol(";")) {
            // comando vazio
        }
        while (!peekKeyword("END")) {
            statements.add(psqlStatement());
            if (peekKeyword("END")) {
                break;
            }
            if (consumeSymbol(";")) {
                while (consumeSymbol(";")) {
                    // comando vazio
                }
                continue;
            }
            Token previous = tokens.get(cursor - 1);
            if (previous.kind == TokenKind.IDENT && previous.text.equalsIgnoreCase("END")) {
                continue;
            }
            expectSymbol(";");
        }
        expectKeyword("END");
        return new PsqlBlock(statements);
    }

    private Psql psqlStatement() {
        if (peekKeyword("BEGIN")) {
            return block();
        }
        if (consumeKeyword("IF")) {
            expectSymbol("(");
            Expr condition = expression();
            expectSymbol(")");
            expectKeyword("THEN");
            Psql thenBranch = psqlStatement();
            if (peekSymbol(";") && peekKeyword(1, "ELSE")) {
                consumeSymbol(";");
            }
            Psql elseBranch = null;
            if (consumeKeyword("ELSE")) {
                elseBranch = psqlStatement();
            }
            return new PsqlIf(condition, thenBranch, elseBranch);
        }
        if (consumeKeyword("WHILE")) {
            expectSymbol("(");
            Expr condition = expression();
            expectSymbol(")");
            expectKeyword("DO");
            return new PsqlWhile(condition, psqlStatement());
        }
        if (consumeKeyword("EXIT")) {
            return new PsqlExit();
        }
        if (consumeKeyword("SUSPEND")) {
            return new PsqlSuspend();
        }
        if (consumeKeyword("EXCEPTION")) {
            return new PsqlException(expression());
        }
        if (consumeKeyword("INSERT")) {
            return new PsqlInsert(insert());
        }
        if (consumeKeyword("UPDATE")) {
            return new PsqlUpdate(update());
        }
        if (consumeKeyword("DELETE")) {
            return new PsqlDelete(delete());
        }
        if (consumeKeyword("EXECUTE")) {
            return new PsqlExecute((ExecuteProcedure) executeProcedure(true));
        }
        if (consumeKeyword("OPEN")) {
            return new PsqlOpen(expectIdent());
        }
        if (consumeKeyword("FETCH")) {
            String name = expectIdent();
            return new PsqlFetch(name, intoTargets());
        }
        if (consumeKeyword("CLOSE")) {
            return new PsqlClose(expectIdent());
        }
        if (consumeKeyword("FOR")) {
            return forSelect();
        }
        if (consumeKeyword("SELECT")) {
            return selectInto();
        }
        if (looksLikeAssignment()) {
            return assignment();
        }
        throw error("comando inválido na rotina");
    }

    private Psql selectInto() {
        List<Expr> expressions = new ArrayList<>();
        do {
            expressions.add(expression());
        } while (consumeSymbol(","));
        List<String> targets = intoTargets();
        expectKeyword("FROM");
        String table = expectRelation();
        if (peekSymbol("(")) {
            throw error("SELECT INTO não chama procedure");
        }
        Expr where = null;
        if (consumeKeyword("WHERE")) {
            where = expression();
        }
        return new PsqlSelectInto(expressions, targets, table, where);
    }

    private Psql forSelect() {
        Select query = cursorQuery();
        List<String> targets = intoTargets();
        expectKeyword("DO");
        return new PsqlForSelect(query, targets, psqlStatement());
    }

    private List<String> intoTargets() {
        expectKeyword("INTO");
        List<String> targets = new ArrayList<>();
        do {
            consumeSymbol(":");
            targets.add(expectIdent());
        } while (consumeSymbol(","));
        return targets;
    }

    private Select cursorQuery() {
        expectKeyword("SELECT");
        boolean distinct = consumeKeyword("DISTINCT");
        boolean star = false;
        List<SelectItem> items = new ArrayList<>();
        if (peekSymbol("*")) {
            consumeSymbol("*");
            star = true;
            if (peekSymbol(",")) {
                throw error("SELECT * não combina com outras expressões");
            }
        } else {
            do {
                int start = current().start;
                Expr expr = expression();
                String alias = null;
                if (consumeKeyword("AS")) {
                    alias = expectIdent();
                } else if (current().kind == TokenKind.IDENT && !peekClause()) {
                    alias = expectIdent();
                }
                int end = tokens.get(cursor - 1).end;
                String label = alias != null ? alias : sql.substring(start, end).trim().replaceAll("\\s+", " ");
                items.add(new SelectItem(expr, alias, label));
            } while (consumeSymbol(","));
        }
        expectKeyword("FROM");
        if (peekKeyword("JOIN") || peekKeyword(1, "JOIN")) {
            throw error("JOIN não é suportado");
        }
        String table = expectRelation();
        if (peekSymbol("(")) {
            throw error("cursor não chama procedure");
        }
        String alias = null;
        if (consumeKeyword("AS")) {
            alias = expectIdent();
        } else if (current().kind == TokenKind.IDENT && !peekClause()) {
            alias = expectIdent();
        }
        if (peekKeyword("JOIN") || consumeKeyword("JOIN")) {
            throw error("JOIN não é suportado");
        }
        Expr where = null;
        if (consumeKeyword("WHERE")) {
            where = expression();
        }
        if (peekKeyword("GROUP") || peekKeyword("HAVING") || peekKeyword("UNION")) {
            throw error("GROUP BY, HAVING e UNION não são suportados");
        }
        List<OrderItem> order = new ArrayList<>();
        if (consumeKeyword("ORDER")) {
            expectKeyword("BY");
            do {
                Expr expr = expression();
                boolean desc = consumeKeyword("DESC");
                if (!desc) {
                    consumeKeyword("ASC");
                }
                order.add(new OrderItem(expr, desc));
            } while (consumeSymbol(","));
        }
        if (peekKeyword("LIMIT") || peekKeyword("OFFSET")) {
            throw error("cursor aceita WHERE e ORDER BY");
        }
        return new Select(distinct, star, items, table, alias, where, order, null, null);
    }

    private boolean looksLikeAssignment() {
        if (peekSymbol(":")) {
            return true;
        }
        if (current().kind != TokenKind.IDENT) {
            return false;
        }
        if (peekSymbol(1, "=") || (peekSymbol(1, ":") && peekSymbol(2, "="))) {
            return true;
        }
        return peekSymbol(1, ".");
    }

    private Psql assignment() {
        String qualifier = null;
        String name;
        if (consumeSymbol(":")) {
            name = expectIdent();
        } else {
            name = expectIdent();
            if (consumeSymbol(".")) {
                qualifier = name;
                name = expectIdent();
            }
        }
        if (consumeSymbol(":")) {
            expectSymbol("=");
        } else {
            expectSymbol("=");
        }
        return new PsqlAssign(qualifier, name, expression());
    }

    private boolean ifExists() {
        if (consumeKeyword("IF")) {
            expectKeyword("EXISTS");
            return true;
        }
        return false;
    }

    private boolean ifNotExists() {
        if (consumeKeyword("IF")) {
            expectKeyword("NOT");
            expectKeyword("EXISTS");
            return true;
        }
        return false;
    }

    private long signedLong(String label) {
        boolean negative = consumeSymbol("-");
        long value = expectLong(label);
        if (negative) {
            try {
                value = Math.negateExact(value);
            } catch (ArithmeticException exception) {
                throw error(label + " grande demais");
            }
        }
        return value;
    }

    private Insert insert() {
        expectKeyword("INTO");
        String table = expectRelation();
        List<String> columns = List.of();
        if (peekSymbol("(")) {
            columns = nameList();
        }
        if (peekKeyword("SELECT")) {
            throw error("INSERT ... SELECT não é suportado");
        }
        expectKeyword("VALUES");
        List<List<Expr>> rows = new ArrayList<>();
        do {
            expectSymbol("(");
            List<Expr> values = new ArrayList<>();
            if (!peekSymbol(")")) {
                do {
                    values.add(expression());
                } while (consumeSymbol(","));
            }
            expectSymbol(")");
            if (values.isEmpty()) {
                throw error("VALUES precisa de ao menos um valor");
            }
            rows.add(values);
        } while (consumeSymbol(","));
        return new Insert(table, columns, rows);
    }

    private Update update() {
        String table = expectRelation();
        expectKeyword("SET");
        List<Assignment> sets = new ArrayList<>();
        do {
            String column = expectIdent();
            expectSymbol("=");
            sets.add(new Assignment(column, expression()));
        } while (consumeSymbol(","));
        Expr where = null;
        if (consumeKeyword("WHERE")) {
            where = expression();
        }
        return new Update(table, sets, where);
    }

    private Delete delete() {
        expectKeyword("FROM");
        String table = expectRelation();
        Expr where = null;
        if (consumeKeyword("WHERE")) {
            where = expression();
        }
        return new Delete(table, where);
    }

    private Stmt select() {
        boolean distinct = consumeKeyword("DISTINCT");
        boolean star = false;
        List<SelectItem> items = new ArrayList<>();
        if (peekSymbol("*")) {
            consumeSymbol("*");
            star = true;
            if (peekSymbol(",")) {
                throw error("SELECT * não combina com outras expressões");
            }
        } else {
            do {
                int start = current().start;
                Expr expr = expression();
                String alias = null;
                if (consumeKeyword("AS")) {
                    alias = expectIdent();
                } else if (current().kind == TokenKind.IDENT && !peekClause()) {
                    alias = expectIdent();
                }
                int end = tokens.get(cursor - 1).end;
                String label = alias != null ? alias : sql.substring(start, end).trim().replaceAll("\\s+", " ");
                items.add(new SelectItem(expr, alias, label));
            } while (consumeSymbol(","));
        }
        if (!peekKeyword("FROM")) {
            if (star) {
                throw error("SELECT * precisa de uma tabela");
            }
            SelectTail tail = selectTail();
            return new Select(distinct, false, items, null, null, tail.where, tail.order, tail.limit, tail.offset);
        }
        expectKeyword("FROM");
        if (peekKeyword("JOIN") || peekKeyword(1, "JOIN")) {
            throw error("JOIN não é suportado");
        }
        String first = expectIdent();
        String table;
        if (peekSymbol("(")) {
            table = first;
        } else if (consumeSymbol(".")) {
            table = first + "." + expectIdent();
        } else {
            table = first;
        }
        if (consumeSymbol("(")) {
            if (table.indexOf('.') >= 0) {
                throw error("procedure não aceita esquema");
            }
            List<Expr> arguments = new ArrayList<>();
            if (!peekSymbol(")")) {
                do {
                    arguments.add(expression());
                } while (consumeSymbol(","));
            }
            expectSymbol(")");
            String procedureAlias = null;
            if (consumeKeyword("AS")) {
                procedureAlias = expectIdent();
            } else if (current().kind == TokenKind.IDENT && !peekClause()) {
                procedureAlias = expectIdent();
            }
            if (peekKeyword("JOIN") || consumeKeyword("JOIN")) {
                throw error("JOIN não é suportado");
            }
            Expr procedureWhere = null;
            if (consumeKeyword("WHERE")) {
                procedureWhere = expression();
            }
            if (peekKeyword("GROUP") || peekKeyword("HAVING") || peekKeyword("UNION")) {
                throw error("GROUP BY, HAVING e UNION não são suportados");
            }
            List<OrderItem> procedureOrder = new ArrayList<>();
            if (consumeKeyword("ORDER")) {
                expectKeyword("BY");
                do {
                    Expr expr = expression();
                    boolean desc = consumeKeyword("DESC");
                    if (!desc) {
                        consumeKeyword("ASC");
                    }
                    procedureOrder.add(new OrderItem(expr, desc));
                } while (consumeSymbol(","));
            }
            Long procedureLimit = null;
            Long procedureOffset = null;
            if (consumeKeyword("LIMIT")) {
                procedureLimit = expectLong("LIMIT");
                if (consumeKeyword("OFFSET")) {
                    procedureOffset = expectLong("OFFSET");
                }
            }
            if (consumeKeyword("OFFSET")) {
                if (procedureOffset != null) {
                    throw error("OFFSET repetido");
                }
                procedureOffset = expectLong("OFFSET");
            }
            return new SelectProcedure(distinct, star, items, table, arguments, procedureAlias, procedureWhere,
                    procedureOrder, procedureLimit, procedureOffset);
        }
        String alias = null;
        if (consumeKeyword("AS")) {
            alias = expectIdent();
        } else if (current().kind == TokenKind.IDENT && !peekClause()) {
            alias = expectIdent();
        }
        SelectTail tail = selectTail();
        return new Select(distinct, star, items, table, alias, tail.where, tail.order, tail.limit, tail.offset);
    }

    private SelectTail selectTail() {
        if (peekKeyword("JOIN") || consumeKeyword("JOIN")) {
            throw error("JOIN não é suportado");
        }
        Expr where = null;
        if (consumeKeyword("WHERE")) {
            where = expression();
        }
        if (peekKeyword("GROUP") || peekKeyword("HAVING") || peekKeyword("UNION")) {
            throw error("GROUP BY, HAVING e UNION não são suportados");
        }
        List<OrderItem> order = new ArrayList<>();
        if (consumeKeyword("ORDER")) {
            expectKeyword("BY");
            do {
                Expr expr = expression();
                boolean desc = false;
                if (consumeKeyword("DESC")) {
                    desc = true;
                } else {
                    consumeKeyword("ASC");
                }
                order.add(new OrderItem(expr, desc));
            } while (consumeSymbol(","));
        }
        Long limit = null;
        Long offset = null;
        if (consumeKeyword("LIMIT")) {
            limit = expectLong("LIMIT");
            if (consumeKeyword("OFFSET")) {
                offset = expectLong("OFFSET");
            }
        }
        if (consumeKeyword("OFFSET")) {
            if (offset != null) {
                throw error("OFFSET repetido");
            }
            offset = expectLong("OFFSET");
        }
        return new SelectTail(where, order, limit, offset);
    }

    private record SelectTail(Expr where, List<OrderItem> order, Long limit, Long offset) {
    }

    private ColDef column() {
        String name = expectIdent();
        TypeSpec type = parseType();
        boolean notNull = false;
        boolean primary = false;
        boolean unique = false;
        boolean auto = false;
        boolean hasDefault = false;
        Object def = null;
        boolean explicitNull = false;
        while (true) {
            if (consumeKeyword("PRIMARY")) {
                expectKeyword("KEY");
                primary = true;
                notNull = true;
            } else if (consumeKeyword("NOT")) {
                expectKeyword("NULL");
                notNull = true;
            } else if (consumeKeyword("NULL")) {
                explicitNull = true;
            } else if (peekKeyword("UNIQUE") && !peekSymbol(1, "(")) {
                consumeKeyword("UNIQUE");
                unique = true;
            } else if (consumeKeyword("AUTOINCREMENT") || consumeKeyword("IDENTITY")) {
                auto = true;
            } else if (consumeKeyword("DEFAULT")) {
                hasDefault = true;
                def = parseDefault();
            } else {
                break;
            }
        }
        if (explicitNull && notNull) {
            throw error("coluna " + name + " não pode ser NULL e NOT NULL");
        }
        if (auto && !type.type.exactInteger()) {
            throw error("AUTOINCREMENT só é permitido em SMALLINT, INTEGER ou BIGINT");
        }
        validateType(name, type);
        return new ColDef(name, type.type, type.length, type.scale, notNull, primary, unique, auto, hasDefault, def);
    }

    private Object parseDefault() {
        if (consumeSymbol("-")) {
            Token number = expectNumber();
            return negate(numberText(number));
        }
        if (consumeSymbol("+")) {
            return numberValue(expectNumber());
        }
        if (consumeKeyword("NULL")) {
            return null;
        }
        if (consumeKeyword("TRUE")) {
            return true;
        }
        if (consumeKeyword("FALSE")) {
            return false;
        }
        Token token = current();
        if (token.kind == TokenKind.NUMBER) {
            cursor++;
            return numberValue(token);
        }
        if (token.kind == TokenKind.STRING) {
            cursor++;
            return token.text;
        }
        if (token.kind == TokenKind.HEX) {
            cursor++;
            return token.hex;
        }
        throw error("DEFAULT aceita apenas um literal");
    }

    private TypeSpec parseType() {
        if (consumeKeyword("SMALLINT")) {
            return new TypeSpec(DataType.SMALLINT, 0, 0);
        }
        if (consumeKeyword("BIGINT")) {
            return new TypeSpec(DataType.BIGINT, 0, 0);
        }
        if (consumeKeyword("INT") || consumeKeyword("INTEGER")) {
            return new TypeSpec(DataType.INTEGER, 0, 0);
        }
        if (consumeKeyword("FLOAT") || consumeKeyword("REAL")) {
            return new TypeSpec(DataType.FLOAT, 0, 0);
        }
        if (consumeKeyword("DOUBLE")) {
            consumeKeyword("PRECISION");
            return new TypeSpec(DataType.DOUBLE, 0, 0);
        }
        if (consumeKeyword("BOOLEAN") || consumeKeyword("BOOL")) {
            return new TypeSpec(DataType.BOOLEAN, 0, 0);
        }
        if (consumeKeyword("VARCHAR")) {
            return new TypeSpec(DataType.VARCHAR, expectLength(), 0);
        }
        if (consumeKeyword("CHAR") || consumeKeyword("CHARACTER")) {
            if (consumeKeyword("VARYING")) {
                return new TypeSpec(DataType.VARCHAR, expectLength(), 0);
            }
            return new TypeSpec(DataType.CHAR, expectLength(), 0);
        }
        if (consumeKeyword("DATE")) {
            return new TypeSpec(DataType.DATE, 0, 0);
        }
        if (consumeKeyword("TIME")) {
            return new TypeSpec(DataType.TIME, 0, 0);
        }
        if (consumeKeyword("TIMESTAMP")) {
            return new TypeSpec(DataType.TIMESTAMP, 0, 0);
        }
        if (consumeKeyword("DECIMAL") || consumeKeyword("NUMERIC")) {
            expectSymbol("(");
            int precision = expectInt("precisão");
            int scale = 0;
            if (consumeSymbol(",")) {
                scale = expectInt("escala");
            }
            expectSymbol(")");
            return new TypeSpec(DataType.DECIMAL, precision, scale);
        }
        if (consumeKeyword("BLOB") || consumeKeyword("BYTEA") || consumeKeyword("BINARY") || consumeKeyword("VARBINARY")) {
            if (peekSymbol("(")) {
                expectLength();
            }
            return new TypeSpec(DataType.BLOB, 0, 0);
        }
        throw error("tipo de dado desconhecido");
    }

    private void validateType(String name, TypeSpec type) {
        if ((type.type == DataType.CHAR || type.type == DataType.VARCHAR)
                && (type.length < 1 || type.length > Format.MAX_CHAR)) {
            throw error("tamanho de " + name + " deve estar entre 1 e " + Format.MAX_CHAR);
        }
        if (type.type == DataType.DECIMAL && (type.length < 1 || type.length > 38 || type.scale < 0 || type.scale > type.length)) {
            throw error("DECIMAL inválido em " + name + " (precisão 1..38 e escala até a precisão)");
        }
    }

    private Expr expression() {
        return parseOr();
    }

    private Expr parseOr() {
        Expr expr = parseAnd();
        while (consumeKeyword("OR")) {
            expr = new Binary(BinaryOp.OR, expr, parseAnd());
        }
        return expr;
    }

    private Expr parseAnd() {
        Expr expr = parseNot();
        while (consumeKeyword("AND")) {
            expr = new Binary(BinaryOp.AND, expr, parseNot());
        }
        return expr;
    }

    private Expr parseNot() {
        if (consumeKeyword("NOT")) {
            return new Unary(UnaryOp.NOT, parseNot());
        }
        return parsePredicate();
    }

    private Expr parsePredicate() {
        Expr left = parseConcat();
        if (consumeKeyword("IS")) {
            boolean negated = consumeKeyword("NOT");
            expectKeyword("NULL");
            return new IsNull(left, negated);
        }
        boolean negated = false;
        if (peekKeyword("NOT") && (peekKeyword(1, "LIKE") || peekKeyword(1, "IN") || peekKeyword(1, "BETWEEN"))) {
            consumeKeyword("NOT");
            negated = true;
        }
        if (consumeKeyword("LIKE")) {
            Expr pattern = parseConcat();
            Expr escape = null;
            if (consumeKeyword("ESCAPE")) {
                escape = parseConcat();
            }
            return new Like(left, pattern, escape, negated);
        }
        if (consumeKeyword("IN")) {
            expectSymbol("(");
            List<Expr> values = new ArrayList<>();
            if (!peekSymbol(")")) {
                do {
                    values.add(expression());
                } while (consumeSymbol(","));
            }
            expectSymbol(")");
            if (values.isEmpty()) {
                throw error("IN precisa de ao menos um valor");
            }
            return new InList(left, values, negated);
        }
        if (consumeKeyword("BETWEEN")) {
            Expr low = parseConcat();
            expectKeyword("AND");
            Expr high = parseConcat();
            return new Between(left, low, high, negated);
        }
        if (negated) {
            throw error("NOT precisa acompanhar LIKE, IN ou BETWEEN");
        }
        BinaryOp op = comparisonOp();
        if (op == null) {
            return left;
        }
        cursor++;
        return new Binary(op, left, parseConcat());
    }

    private BinaryOp comparisonOp() {
        if (peekSymbol("=")) {
            return BinaryOp.EQ;
        }
        if (peekSymbol("<>") || peekSymbol("!=")) {
            return BinaryOp.NE;
        }
        if (peekSymbol("<=")) {
            return BinaryOp.LE;
        }
        if (peekSymbol(">=")) {
            return BinaryOp.GE;
        }
        if (peekSymbol("<")) {
            return BinaryOp.LT;
        }
        if (peekSymbol(">")) {
            return BinaryOp.GT;
        }
        return null;
    }

    private Expr parseConcat() {
        Expr expr = parseAdd();
        while (consumeSymbol("||")) {
            expr = new Binary(BinaryOp.CONCAT, expr, parseAdd());
        }
        return expr;
    }

    private Expr parseAdd() {
        Expr expr = parseMul();
        while (true) {
            if (consumeSymbol("+")) {
                expr = new Binary(BinaryOp.ADD, expr, parseMul());
            } else if (consumeSymbol("-")) {
                expr = new Binary(BinaryOp.SUB, expr, parseMul());
            } else {
                return expr;
            }
        }
    }

    private Expr parseMul() {
        Expr expr = parseUnary();
        while (true) {
            if (consumeSymbol("*")) {
                expr = new Binary(BinaryOp.MUL, expr, parseUnary());
            } else if (consumeSymbol("/")) {
                expr = new Binary(BinaryOp.DIV, expr, parseUnary());
            } else if (consumeSymbol("%")) {
                expr = new Binary(BinaryOp.MOD, expr, parseUnary());
            } else {
                return expr;
            }
        }
    }

    private Expr parseUnary() {
        if (consumeSymbol("+")) {
            return new Unary(UnaryOp.PLUS, parseUnary());
        }
        if (consumeSymbol("-")) {
            return new Unary(UnaryOp.NEGATE, parseUnary());
        }
        if (consumeKeyword("NOT")) {
            return new Unary(UnaryOp.NOT, parseUnary());
        }
        return parsePrimary();
    }

    private Expr parsePrimary() {
        Token token = current();
        if (token.kind == TokenKind.NUMBER) {
            cursor++;
            return new Literal(numberValue(token));
        }
        if (token.kind == TokenKind.STRING) {
            cursor++;
            return new Literal(token.text);
        }
        if (token.kind == TokenKind.HEX) {
            cursor++;
            return new Literal(token.hex);
        }
        if (token.kind == TokenKind.PARAM) {
            cursor++;
            return new Param(token.parameter);
        }
        if (consumeKeyword("NULL")) {
            return new Literal(null);
        }
        if (consumeKeyword("TRUE")) {
            return new Literal(true);
        }
        if (consumeKeyword("FALSE")) {
            return new Literal(false);
        }
        if (consumeSymbol("(")) {
            Expr expr = expression();
            expectSymbol(")");
            return expr;
        }
        if (consumeSymbol(":")) {
            return new VarRef(expectIdent());
        }
        if (peekKeyword("NEXT") && peekKeyword(1, "VALUE")) {
            consumeKeyword("NEXT");
            expectKeyword("VALUE");
            expectKeyword("FOR");
            return new NextValue(expectIdent());
        }
        if (token.kind == TokenKind.IDENT && !token.quoted && contextVariable(token.text) && !peekSymbol(".")) {
            String name = token.text.toUpperCase(Locale.ROOT);
            cursor++;
            Integer precision = null;
            if (consumeSymbol("(")) {
                if (!timedContext(name)) {
                    throw error(name + " não aceita argumentos");
                }
                precision = expectInt("precisão");
                if (precision < 0 || precision > 3) {
                    throw error("precisão de " + name + " deve estar entre 0 e 3");
                }
                expectSymbol(")");
            }
            return new ContextRef(name, precision);
        }
        if (token.kind == TokenKind.IDENT) {
            String name = token.text;
            cursor++;
            if (consumeSymbol("(")) {
                return call(name);
            }
            String qualifier = null;
            if (consumeSymbol(".")) {
                qualifier = name;
                name = expectIdent();
                if (consumeSymbol(".")) {
                    qualifier = qualifier + "." + name;
                    name = expectIdent();
                }
            }
            return new ColumnRef(qualifier, name);
        }
        throw error("expressão inválida");
    }

    private static boolean contextVariable(String name) {
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER", "CURRENT_ROLE",
                    "CURRENT_CONNECTION", "CURRENT_TRANSACTION", "LOCALTIME", "LOCALTIMESTAMP", "USER" -> true;
            default -> false;
        };
    }

    private static boolean timedContext(String name) {
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "CURRENT_TIME", "CURRENT_TIMESTAMP", "LOCALTIME", "LOCALTIMESTAMP" -> true;
            default -> false;
        };
    }

    private Expr call(String name) {
        boolean distinct = consumeKeyword("DISTINCT");
        boolean star = false;
        List<Expr> args = new ArrayList<>();
        if (consumeSymbol("*")) {
            star = true;
            if (distinct) {
                throw error("DISTINCT não combina com *");
            }
        } else if (!peekSymbol(")")) {
            do {
                args.add(expression());
            } while (consumeSymbol(","));
        }
        expectSymbol(")");
        return new Call(name, distinct, star, args);
    }

    private List<String> columnList() {
        return nameList();
    }

    private List<String> nameList() {
        expectSymbol("(");
        List<String> names = new ArrayList<>();
        do {
            names.add(expectIdent());
        } while (consumeSymbol(","));
        expectSymbol(")");
        if (names.isEmpty()) {
            throw error("a lista de colunas está vazia");
        }
        return names;
    }

    private int expectLength() {
        expectSymbol("(");
        int length = expectInt("tamanho");
        expectSymbol(")");
        return length;
    }

    private int expectInt(String label) {
        long value = expectLong(label);
        if (value > Integer.MAX_VALUE) {
            throw error(label + " grande demais");
        }
        return (int) value;
    }

    private long expectLong(String label) {
        Token token = current();
        if (token.kind != TokenKind.NUMBER) {
            throw error("esperado número em " + label);
        }
        cursor++;
        BigDecimal number = new BigDecimal(token.text);
        try {
            return number.longValueExact();
        } catch (ArithmeticException exception) {
            throw error(label + " precisa ser um inteiro");
        }
    }

    private String expectRelation() {
        String name = expectIdent();
        if (consumeSymbol(".")) {
            name = name + "." + expectIdent();
        }
        return name;
    }

    private String expectIdent() {
        Token token = current();
        if (token.kind != TokenKind.IDENT) {
            throw error("esperado identificador");
        }
        Names.checkName(token.text, "identificador");
        cursor++;
        return token.text;
    }

    private Token expectNumber() {
        Token token = current();
        if (token.kind != TokenKind.NUMBER) {
            throw error("esperado número");
        }
        cursor++;
        return token;
    }

    private void expectKeyword(String keyword) {
        if (!consumeKeyword(keyword)) {
            throw error("esperado " + keyword);
        }
    }

    private void expectSymbol(String symbol) {
        if (!consumeSymbol(symbol)) {
            throw error("esperado '" + symbol + "'");
        }
    }

    private boolean optionalKeyword(String keyword) {
        return consumeKeyword(keyword);
    }

    private boolean consumeKeyword(String keyword) {
        if (!peekKeyword(keyword)) {
            return false;
        }
        cursor++;
        return true;
    }

    private boolean consumeSymbol(String symbol) {
        if (!peekSymbol(symbol)) {
            return false;
        }
        cursor++;
        return true;
    }

    private boolean peekClause() {
        return peekKeyword("FROM") || peekKeyword("WHERE") || peekKeyword("ORDER") || peekKeyword("GROUP")
                || peekKeyword("HAVING") || peekKeyword("LIMIT") || peekKeyword("OFFSET") || peekKeyword("UNION")
                || peekKeyword("JOIN") || peekKeyword("SET") || peekKeyword("INTO") || peekKeyword("DO");
    }

    private boolean peekKeyword(String keyword) {
        return peekKeyword(0, keyword);
    }

    private boolean peekKeyword(int ahead, String keyword) {
        if (cursor + ahead >= tokens.size()) {
            return false;
        }
        Token token = tokens.get(cursor + ahead);
        return token.kind == TokenKind.IDENT && !token.quoted && token.text.equalsIgnoreCase(keyword);
    }

    private boolean peekSymbol(String symbol) {
        return peekSymbol(0, symbol);
    }

    private boolean peekSymbol(int ahead, String symbol) {
        if (cursor + ahead >= tokens.size()) {
            return false;
        }
        Token token = tokens.get(cursor + ahead);
        return token.kind == TokenKind.SYMBOL && token.text.equals(symbol);
    }

    private boolean peek(TokenKind kind) {
        return current().kind == kind;
    }

    private Token current() {
        return tokens.get(cursor);
    }

    private Object numberValue(Token token) {
        String text = numberText(token);
        if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
            try {
                long number = Long.parseLong(text);
                if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
                    return (int) number;
                }
                return number;
            } catch (NumberFormatException exception) {
                return new BigDecimal(text);
            }
        }
        return new BigDecimal(text);
    }

    private String numberText(Token token) {
        return token.text;
    }

    private Object negate(String text) {
        return numberValue(new Token(TokenKind.NUMBER, "-" + text, null, 0, 0, false, -1));
    }

    private NexException error(String message) {
        Token token = current();
        int position = Math.min(token.start, sql.length());
        String near = sql.substring(position, Math.min(sql.length(), position + 24)).replace('\n', ' ');
        return new NexException(message + " próximo a '" + near + "' (posição " + (position + 1) + ")");
    }

    private void lex() {
        int index = 0;
        int length = sql.length();
        while (index < length) {
            char current = sql.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            if (current == '-' && index + 1 < length && sql.charAt(index + 1) == '-') {
                index += 2;
                while (index < length && sql.charAt(index) != '\n') {
                    index++;
                }
                continue;
            }
            if (current == '/' && index + 1 < length && sql.charAt(index + 1) == '*') {
                int close = sql.indexOf("*/", index + 2);
                if (close < 0) {
                    throw new NexException("comentário não terminado");
                }
                index = close + 2;
                continue;
            }
            int start = index;
            if (current == '\'') {
                index++;
                StringBuilder text = new StringBuilder();
                while (index < length) {
                    char ch = sql.charAt(index++);
                    if (ch == '\'') {
                        if (index < length && sql.charAt(index) == '\'') {
                            text.append('\'');
                            index++;
                            continue;
                        }
                        tokens.add(new Token(TokenKind.STRING, text.toString(), null, start, index, false, -1));
                        break;
                    }
                    text.append(ch);
                }
                if (tokens.isEmpty() || tokens.get(tokens.size() - 1).start != start) {
                    throw new NexException("texto não terminado (posição " + (start + 1) + ")");
                }
                continue;
            }
            if ((current == 'X' || current == 'x') && index + 1 < length && sql.charAt(index + 1) == '\'') {
                index += 2;
                int hexStart = index;
                while (index < length && sql.charAt(index) != '\'') {
                    index++;
                }
                if (index >= length) {
                    throw new NexException("literal hexadecimal não terminado");
                }
                String hex = sql.substring(hexStart, index);
                index++;
                tokens.add(new Token(TokenKind.HEX, hex, decodeHex(hex, start), start, index, false, -1));
                continue;
            }
            if (current == '?' ) {
                tokens.add(new Token(TokenKind.PARAM, "?", null, start, index + 1, false, parameters++));
                index++;
                continue;
            }
            if (current == '<' && index + 1 < length && sql.charAt(index + 1) == '>') {
                tokens.add(symbol("<>", start, index + 2));
                index += 2;
                continue;
            }
            if (current == '!' && index + 1 < length && sql.charAt(index + 1) == '=') {
                tokens.add(symbol("!=", start, index + 2));
                index += 2;
                continue;
            }
            if ((current == '<' || current == '>') && index + 1 < length && sql.charAt(index + 1) == '=') {
                tokens.add(symbol(sql.substring(start, start + 2), start, index + 2));
                index += 2;
                continue;
            }
            if (current == '|' && index + 1 < length && sql.charAt(index + 1) == '|') {
                tokens.add(symbol("||", start, index + 2));
                index += 2;
                continue;
            }
            if ("(),;.*+/=%-:".indexOf(current) >= 0) {
                tokens.add(symbol(String.valueOf(current), start, index + 1));
                index++;
                continue;
            }
            if (current == '<' || current == '>') {
                tokens.add(symbol(String.valueOf(current), start, index + 1));
                index++;
                continue;
            }
            if (Character.isDigit(current) || (current == '.' && index + 1 < length && Character.isDigit(sql.charAt(index + 1)))) {
                index++;
                while (index < length && Character.isDigit(sql.charAt(index))) {
                    index++;
                }
                if (index < length && sql.charAt(index) == '.' && index + 1 < length && Character.isDigit(sql.charAt(index + 1))) {
                    index++;
                    while (index < length && Character.isDigit(sql.charAt(index))) {
                        index++;
                    }
                }
                if (index < length && (sql.charAt(index) == 'e' || sql.charAt(index) == 'E')) {
                    int exponent = index + 1;
                    if (exponent < length && (sql.charAt(exponent) == '+' || sql.charAt(exponent) == '-')) {
                        exponent++;
                    }
                    int digits = exponent;
                    while (digits < length && Character.isDigit(sql.charAt(digits))) {
                        digits++;
                    }
                    if (digits > exponent) {
                        index = digits;
                    }
                }
                tokens.add(new Token(TokenKind.NUMBER, sql.substring(start, index), null, start, index, false, -1));
                continue;
            }
            if (current == '"') {
                index++;
                StringBuilder text = new StringBuilder();
                while (index < length) {
                    char ch = sql.charAt(index++);
                    if (ch == '"') {
                        if (index < length && sql.charAt(index) == '"') {
                            text.append('"');
                            index++;
                            continue;
                        }
                        tokens.add(new Token(TokenKind.IDENT, text.toString(), null, start, index, true, -1));
                        break;
                    }
                    text.append(ch);
                }
                if (tokens.isEmpty() || tokens.get(tokens.size() - 1).start != start) {
                    throw new NexException("identificador não terminado (posição " + (start + 1) + ")");
                }
                continue;
            }
            if (Character.isLetter(current) || current == '_') {
                index++;
                while (index < length) {
                    char ch = sql.charAt(index);
                    if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '$') {
                        index++;
                    } else {
                        break;
                    }
                }
                tokens.add(new Token(TokenKind.IDENT, sql.substring(start, index), null, start, index, false, -1));
                continue;
            }
            throw new NexException("caractere inválido '" + current + "' (posição " + (start + 1) + ")");
        }
        tokens.add(new Token(TokenKind.EOF, "", null, sql.length(), sql.length(), false, -1));
    }

    private static byte[] decodeHex(String hex, int position) {
        if ((hex.length() % 2) != 0) {
            throw new NexException("literal hexadecimal incompleto (posição " + (position + 1) + ")");
        }
        byte[] data = new byte[hex.length() / 2];
        for (int i = 0; i < data.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new NexException("dígito hexadecimal inválido (posição " + (position + 1) + ")");
            }
            data[i] = (byte) ((high << 4) + low);
        }
        return data;
    }

    private Token symbol(String text, int start, int end) {
        return new Token(TokenKind.SYMBOL, text, null, start, end, false, -1);
    }

    private enum TokenKind {
        EOF, IDENT, STRING, NUMBER, HEX, PARAM, SYMBOL
    }

    private record Token(TokenKind kind, String text, byte[] hex, int start, int end, boolean quoted, int parameter) {
    }

    private record TypeSpec(DataType type, int length, int scale) {
    }
}
