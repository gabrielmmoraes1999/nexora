package io.github.gabrielmmoraes1999.nexora;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
            throw new NexException("empty SQL");
        }
        SqlParser parser = new SqlParser(sql);
        parser.lex();
        return new ParsedSql(parser.script(), parser.parameters);
    }

    static Select parseView(String source) {
        if (source == null || source.isBlank()) {
            throw new NexException("view without SELECT");
        }
        SqlParser parser = new SqlParser(source);
        parser.lex();
        if (!parser.peekKeyword("SELECT")) {
            throw new NexException("view expects SELECT");
        }
        parser.expectKeyword("SELECT");
        Query body = parser.selectQuery();
        if (parser.parameters != 0) {
            throw new NexException("view does not accept parameters");
        }
        parser.skipSemicolons();
        if (!parser.peek(TokenKind.EOF)) {
            throw new NexException("text after the view SELECT");
        }
        if (body instanceof SelectProcedure) {
            throw new NexException("view does not accept a procedure SELECT");
        }
        if (!(body instanceof Select select) || !select.joins().isEmpty()) {
            throw new NexException("view reads one table");
        }
        return select;
    }

    static Routine parseRoutine(String source) {
        if (source == null || source.isBlank()) {
            throw new NexException("routine without a body");
        }
        SqlParser parser = new SqlParser(source);
        parser.lex();
        Routine routine = parser.routineBody();
        parser.skipSemicolons();
        if (!parser.peek(TokenKind.EOF)) {
            throw parser.error("text after the routine END");
        }
        return routine;
    }

    private List<Stmt> script() {
        List<Stmt> statements = new ArrayList<>();
        skipSemicolons();
        if (peek(TokenKind.EOF)) {
            throw new NexException("empty SQL");
        }
        while (!peek(TokenKind.EOF)) {
            statements.add(statement());
            if (consumeSymbol(";")) {
                skipSemicolons();
                continue;
            }
            if (!peek(TokenKind.EOF)) {
                throw error("missing ';' between statements");
            }
        }
        return statements;
    }

    private void skipSemicolons() {
        while (consumeSymbol(";")) {
            // empty statements are ignored
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
        if (consumeKeyword("WITH")) {
            return withQuery();
        }
        if (consumeKeyword("SELECT")) {
            return asStatement(selectQuery());
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
        throw error("unrecognized SQL statement");
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
                throw error("duplicate privilege: " + privilege);
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
        throw error("expected privilege: SELECT, INSERT, UPDATE, DELETE, or ALL");
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
        TableKeys keys = new TableKeys();
        if (!peekSymbol(")")) {
            do {
                if (peekKeyword("CONSTRAINT") || (peekKeyword("PRIMARY") && peekKeyword(1, "KEY"))
                        || (peekKeyword("FOREIGN") && peekKeyword(1, "KEY"))
                        || (peekKeyword("UNIQUE") && peekSymbol(1, "("))) {
                    tableConstraint(primary, keys);
                } else {
                    columns.add(column());
                }
            } while (consumeSymbol(","));
        }
        expectSymbol(")");
        if (columns.isEmpty()) {
            throw error("CREATE TABLE requires at least one column");
        }
        return new CreateTable(ifNotExists, name, columns, primary, List.copyOf(keys.uniques),
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
            throw error("CREATE VIEW expects SELECT");
        }
        int start = current().start;
        int before = parameters;
        expectKeyword("SELECT");
        Query body = selectQuery();
        if (parameters != before) {
            throw error("CREATE VIEW does not accept parameters");
        }
        if (body instanceof SelectProcedure) {
            throw error("CREATE VIEW does not accept a procedure SELECT");
        }
        if (!(body instanceof Select select) || !select.joins().isEmpty()) {
            throw error("CREATE VIEW reads one table");
        }
        if (select.star() && !select.groupBy().isEmpty()) {
            throw error("SELECT * cannot be combined with GROUP BY");
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

    private void tableConstraint(List<String> primary, TableKeys keys) {
        String name = null;
        if (consumeKeyword("CONSTRAINT")) {
            name = expectIdent();
        }
        if (consumeKeyword("PRIMARY")) {
            expectKeyword("KEY");
            if (!primary.isEmpty()) {
                throw error("the table can have only one primary key");
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
        List<String> columns = columnList();
        if (peekKeyword("USING")) {
            throw error("USING INDEX applies to PRIMARY KEY and FOREIGN KEY");
        }
        keys.uniques.add(new UniqueClause(name, columns));
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
                throw error("ON expects DELETE or UPDATE");
            }
            if (delete && sawDelete) {
                throw error("duplicate ON DELETE");
            }
            if (!delete && sawUpdate) {
                throw error("duplicate ON UPDATE");
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
        throw error("referential action expects NO ACTION, RESTRICT, CASCADE, SET NULL, or SET DEFAULT");
    }

    private record Actions(ReferentialAction onUpdate, ReferentialAction onDelete) {
    }

    private static final class TableKeys {
        private String primaryConstraint;
        private String primaryIndex;
        private boolean primaryDescending;
        private final List<UniqueClause> uniques = new ArrayList<>();
        private final List<ForeignClause> foreignKeys = new ArrayList<>();
    }

    private record Using(String name, boolean descending) {
    }

    private Stmt drop() {
        if (consumeKeyword("GENERATOR") || consumeKeyword("SEQUENCE")) {
            return new DropGenerator(ifExists(), expectRelation());
        }
        if (consumeKeyword("PROCEDURE")) {
            return new DropProcedure(ifExists(), expectRelation());
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
            String name = expectRelation();
            String table = null;
            if (consumeKeyword("ON")) {
                table = expectRelation();
            }
            return new DropIndex(ifExists, name, table);
        }
        if (!peekKeyword("TABLE")) {
            throw error("DROP supports TABLE, INDEX, SCHEMA, GENERATOR, SEQUENCE, PROCEDURE, TRIGGER, VIEW, ROLE, and CONNECTION");
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
            throw error("ALTER TRIGGER accepts ACTIVE or INACTIVE");
        }
        if (consumeKeyword("SEQUENCE") || consumeKeyword("GENERATOR")) {
            String name = expectRelation();
            expectKeyword("RESTART");
            expectKeyword("WITH");
            return new SetGenerator(name, expression(), true);
        }
        expectKeyword("TABLE");
        String table = expectRelation();
        if (consumeKeyword("ADD")) {
            if (peekKeyword("CONSTRAINT") || peekKeyword("PRIMARY") || peekKeyword("FOREIGN")
                    || (peekKeyword("UNIQUE") && peekSymbol(1, "("))) {
                return addTableConstraint(table);
            }
            optionalKeyword("COLUMN");
            return new AddColumn(table, column());
        }
        if (consumeKeyword("DROP")) {
            if (consumeKeyword("CONSTRAINT")) {
                return new DropConstraint(table, expectIdent());
            }
            if (consumeKeyword("PRIMARY")) {
                expectKeyword("KEY");
                return new DropPrimaryKey(table);
            }
            if (consumeKeyword("FOREIGN")) {
                expectKeyword("KEY");
                return new DropForeignKey(table, expectIdent());
            }
            if (consumeKeyword("UNIQUE")) {
                return new DropUnique(table, expectIdent());
            }
            optionalKeyword("COLUMN");
            return new DropColumn(table, expectIdent());
        }
        if (consumeKeyword("ALTER")) {
            optionalKeyword("COLUMN");
            String column = expectIdent();
            if (!consumeKeyword("POSITION")) {
                throw error("ALTER COLUMN supports POSITION");
            }
            long raw = signedLong("POSITION");
            if (raw > Integer.MAX_VALUE) {
                throw error("POSITION is too large");
            }
            return new AlterColumnPosition(table, column, (int) raw);
        }
        throw error("ALTER TABLE supports ADD COLUMN, DROP COLUMN, ALTER COLUMN POSITION, and ADD and DROP of PRIMARY KEY, FOREIGN KEY, UNIQUE, and CONSTRAINT");
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
        if (consumeKeyword("UNIQUE")) {
            List<String> columns = columnList();
            if (peekKeyword("USING")) {
                throw error("USING INDEX applies to PRIMARY KEY and FOREIGN KEY");
            }
            return new AddUnique(table, columns, name);
        }
        throw error("ADD CONSTRAINT expects PRIMARY KEY, FOREIGN KEY, or UNIQUE");
    }

    private Stmt setGenerator() {
        expectKeyword("GENERATOR");
        String name = expectRelation();
        expectKeyword("TO");
        return new SetGenerator(name, expression(), false);
    }

    private Stmt createGenerator(boolean sequence) {
        String name = expectRelation();
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
                throw error("INCREMENT BY cannot be zero");
            }
        }
        return new CreateGenerator(name, start, increment, sequence);
    }

    private Stmt createProcedure() {
        String name = expectRelation();
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
                throw error("duplicate event: " + event);
            }
            events.add(event);
        } while (consumeKeyword("OR"));
        int position = 0;
        if (consumeKeyword("POSITION")) {
            long raw = signedLong("POSITION");
            if (raw > Integer.MAX_VALUE || raw < Integer.MIN_VALUE) {
                throw error("POSITION is too large");
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
        throw error("expected event: INSERT, UPDATE, or DELETE");
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
        String name = expectRelation();
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
                throw error("RETURNING_VALUES can only be used inside a procedure or trigger");
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
            Query query = cursorQuery();
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
            // empty statement
        }
        while (!peekKeyword("END")) {
            statements.add(psqlStatement());
            if (peekKeyword("END")) {
                break;
            }
            if (consumeSymbol(";")) {
                while (consumeSymbol(";")) {
                    // empty statement
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
        throw error("invalid statement in the routine");
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
            throw error("SELECT INTO does not call a procedure");
        }
        Expr where = null;
        if (consumeKeyword("WHERE")) {
            where = expression();
        }
        return new PsqlSelectInto(expressions, targets, table, where);
    }

    private Psql forSelect() {
        Query query = cursorQuery();
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

    private Query cursorQuery() {
        Query query;
        if (consumeKeyword("WITH")) {
            query = withQuery();
        } else {
            expectKeyword("SELECT");
            query = selectQuery();
        }
        if (limitOf(query) != null || offsetOf(query) != null) {
            throw error("cursor accepts WHERE and ORDER BY");
        }
        return query;
    }

    private Long limitOf(Query query) {
        return switch (query) {
            case Select select -> select.limit();
            case SelectProcedure procedure -> procedure.limit();
            case UnionQuery union -> union.limit();
            case WithQuery with -> limitOf(with.body());
        };
    }

    private Long offsetOf(Query query) {
        return switch (query) {
            case Select select -> select.offset();
            case SelectProcedure procedure -> procedure.offset();
            case UnionQuery union -> union.offset();
            case WithQuery with -> offsetOf(with.body());
        };
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
                throw error(label + " is too large");
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
            throw error("INSERT ... SELECT is not supported");
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
                throw error("VALUES requires at least one value");
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

    private Stmt asStatement(Query query) {
        return switch (query) {
            case Select select -> select;
            case SelectProcedure procedure -> procedure;
            case UnionQuery union -> union;
            case WithQuery with -> with;
        };
    }

    private Query selectQuery() {
        Query head = selectBody();
        Query combined = head;
        while (peekKeyword("UNION")) {
            consumeKeyword("UNION");
            boolean all = consumeKeyword("ALL");
            expectKeyword("SELECT");
            combined = new UnionQuery(combined, all, selectBody(), List.of(), null, null);
        }
        return applyTail(combined, readTail());
    }

    private WithQuery withQuery() {
        if (peekKeyword("RECURSIVE")) {
            throw error("WITH RECURSIVE is not supported");
        }
        List<Cte> ctes = new ArrayList<>();
        Set<String> names = new HashSet<>();
        do {
            String name = expectIdent();
            if (!names.add(Names.fold(name))) {
                throw error("duplicate CTE: " + name);
            }
            List<String> columns = List.of();
            if (peekSymbol("(")) {
                columns = nameList();
                Set<String> used = new HashSet<>();
                for (String column : columns) {
                    if (!used.add(Names.fold(column))) {
                        throw error("duplicate column: " + column);
                    }
                }
            }
            expectKeyword("AS");
            expectSymbol("(");
            Query body = nestedQuery();
            expectSymbol(")");
            rejectSelfReference(name, body);
            ctes.add(new Cte(name, columns, body));
        } while (consumeSymbol(","));
        return new WithQuery(List.copyOf(ctes), nestedQuery());
    }

    private Query nestedQuery() {
        if (consumeKeyword("WITH")) {
            return withQuery();
        }
        expectKeyword("SELECT");
        return selectQuery();
    }

    private void rejectSelfReference(String name, Query query) {
        switch (query) {
            case Select select -> {
                if (sameCte(select.table(), name)) {
                    throw error("CTE cannot reference itself: " + name);
                }
                for (Join join : select.joins()) {
                    if (sameCte(join.relation(), name)) {
                        throw error("CTE cannot reference itself: " + name);
                    }
                }
            }
            case SelectProcedure procedure -> {
                if (sameCte(procedure.procedure(), name)) {
                    throw error("CTE cannot reference itself: " + name);
                }
            }
            case UnionQuery union -> {
                rejectSelfReference(name, union.left());
                rejectSelfReference(name, union.right());
            }
            case WithQuery with -> {
                boolean shadowed = false;
                for (Cte cte : with.ctes()) {
                    if (Names.same(cte.name(), name)) {
                        shadowed = true;
                        continue;
                    }
                    rejectSelfReference(name, cte.query());
                }
                if (!shadowed) {
                    rejectSelfReference(name, with.body());
                }
            }
        }
    }

    private boolean sameCte(String relation, String name) {
        return relation != null && relation.indexOf('.') < 0 && Names.same(relation, name);
    }

    /**
     * Reads {@code alias.*} or {@code schema.table.*} when that is the next select item.
     * Returns null without consuming tokens when the dot introduces a column.
     */
    private String qualifiedStar() {
        if (current().kind != TokenKind.IDENT) {
            return null;
        }
        int index = cursor + 1;
        if (!symbolAt(index, ".")) {
            return null;
        }
        String qualifier = current().text;
        while (symbolAt(index, ".")) {
            if (symbolAt(index + 1, "*")) {
                cursor = index + 2;
                return qualifier;
            }
            index++;
            if (index >= tokens.size() || tokens.get(index).kind != TokenKind.IDENT) {
                return null;
            }
            qualifier = qualifier + "." + tokens.get(index).text;
            index++;
        }
        return null;
    }

    private boolean symbolAt(int index, String symbol) {
        if (index >= tokens.size()) {
            return false;
        }
        Token token = tokens.get(index);
        return token.kind == TokenKind.SYMBOL && token.text.equals(symbol);
    }

    private Query selectBody() {
        boolean distinct = consumeKeyword("DISTINCT");
        boolean star = false;
        List<SelectItem> items = new ArrayList<>();
        if (peekSymbol("*")) {
            consumeSymbol("*");
            star = true;
            if (peekSymbol(",")) {
                throw error("SELECT * cannot be combined with other expressions");
            }
        } else {
            do {
                if (peekSymbol("*")) {
                    throw error("SELECT * cannot be combined with other expressions");
                }
                String qualified = qualifiedStar();
                if (qualified != null) {
                    if (consumeKeyword("AS") || (current().kind == TokenKind.IDENT && !peekClause())) {
                        throw error("* cannot have an alias");
                    }
                    items.add(new SelectItem(new QualifiedStar(qualified), null, qualified + ".*"));
                    continue;
                }
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
                throw error("SELECT * requires a table");
            }
            Expr where = null;
            if (consumeKeyword("WHERE")) {
                where = expression();
            }
            List<Expr> groupBy = parseGroupBy();
            if (!groupBy.isEmpty()) {
                throw error("GROUP BY requires a table");
            }
            Expr having = parseHaving();
            return new Select(distinct, false, items, null, null, List.of(), where, groupBy, having, List.of(), null, null);
        }
        expectKeyword("FROM");
        if (isJoinStart() || peekKeyword("NATURAL") || peekKeyword("OUTER")) {
            throw error("expected a table");
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
            List<Expr> arguments = new ArrayList<>();
            if (!peekSymbol(")")) {
                do {
                    arguments.add(expression());
                } while (consumeSymbol(","));
            }
            expectSymbol(")");
            String procedureAlias = readAlias();
            if (isJoinStart() || peekKeyword("NATURAL")) {
                throw error("JOIN is not supported on a procedure");
            }
            Expr procedureWhere = null;
            if (consumeKeyword("WHERE")) {
                procedureWhere = expression();
            }
            List<Expr> procedureGroup = parseGroupBy();
            Expr procedureHaving = parseHaving();
            return new SelectProcedure(distinct, star, items, table, arguments, procedureAlias, procedureWhere,
                    procedureGroup, procedureHaving, List.of(), null, null);
        }
        String alias = readAlias();
        List<Join> joins = readJoins();
        Expr where = null;
        if (consumeKeyword("WHERE")) {
            where = expression();
        }
        List<Expr> groupBy = parseGroupBy();
        Expr having = parseHaving();
        return new Select(distinct, star, items, table, alias, joins, where, groupBy, having, List.of(), null, null);
    }

    private Query applyTail(Query query, Tail tail) {
        return switch (query) {
            case Select select -> new Select(select.distinct(), select.star(), select.items(), select.table(), select.alias(),
                    select.joins(), select.where(), select.groupBy(), select.having(), tail.order, tail.limit, tail.offset);
            case SelectProcedure procedure -> new SelectProcedure(procedure.distinct(), procedure.star(), procedure.items(),
                    procedure.procedure(), procedure.arguments(), procedure.alias(), procedure.where(),
                    procedure.groupBy(), procedure.having(), tail.order, tail.limit, tail.offset);
            case UnionQuery union -> new UnionQuery(union.left(), union.all(), union.right(), tail.order, tail.limit, tail.offset);
            case WithQuery with -> throw error("WITH cannot follow SELECT");
        };
    }

    private List<Join> readJoins() {
        List<Join> joins = new ArrayList<>();
        while (isJoinStart() || peekKeyword("NATURAL") || peekKeyword("OUTER")) {
            if (consumeKeyword("NATURAL")) {
                throw error("NATURAL JOIN is not supported");
            }
            if (peekKeyword("OUTER")) {
                throw error("expected LEFT, RIGHT, or FULL");
            }
            joins.add(oneJoin());
        }
        return joins;
    }

    private Join oneJoin() {
        JoinKind kind;
        if (consumeKeyword("CROSS")) {
            expectKeyword("JOIN");
            kind = JoinKind.CROSS;
        } else if (consumeKeyword("INNER")) {
            expectKeyword("JOIN");
            kind = JoinKind.INNER;
        } else if (consumeKeyword("LEFT")) {
            consumeKeyword("OUTER");
            expectKeyword("JOIN");
            kind = JoinKind.LEFT;
        } else if (consumeKeyword("RIGHT")) {
            consumeKeyword("OUTER");
            expectKeyword("JOIN");
            kind = JoinKind.RIGHT;
        } else if (consumeKeyword("FULL")) {
            consumeKeyword("OUTER");
            expectKeyword("JOIN");
            kind = JoinKind.FULL;
        } else {
            expectKeyword("JOIN");
            kind = JoinKind.INNER;
        }
        String relation = expectRelation();
        if (peekSymbol("(")) {
            throw error("JOIN does not call a procedure");
        }
        String alias = readAlias();
        Expr on = null;
        if (kind == JoinKind.CROSS) {
            if (peekKeyword("USING")) {
                throw error("USING is not supported");
            }
            if (consumeKeyword("ON")) {
                throw error("CROSS JOIN does not accept ON");
            }
        } else {
            if (peekKeyword("USING")) {
                throw error("USING is not supported");
            }
            expectKeyword("ON");
            on = expression();
        }
        return new Join(kind, relation, alias, on);
    }

    private boolean isJoinStart() {
        return peekKeyword("JOIN") || peekKeyword("INNER") || peekKeyword("CROSS")
                || peekKeyword("LEFT") || peekKeyword("RIGHT") || peekKeyword("FULL");
    }

    private String readAlias() {
        if (consumeKeyword("AS")) {
            return expectIdent();
        }
        if (current().kind == TokenKind.IDENT && !peekClause()) {
            return expectIdent();
        }
        return null;
    }

    private List<Expr> parseGroupBy() {
        if (!consumeKeyword("GROUP")) {
            return List.of();
        }
        expectKeyword("BY");
        List<Expr> keys = new ArrayList<>();
        do {
            keys.add(expression());
        } while (consumeSymbol(","));
        return keys;
    }

    private Expr parseHaving() {
        if (!consumeKeyword("HAVING")) {
            return null;
        }
        return expression();
    }

    private Tail readTail() {
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
                throw error("duplicate OFFSET");
            }
            offset = expectLong("OFFSET");
        }
        return new Tail(order, limit, offset);
    }

    private record Tail(List<OrderItem> order, Long limit, Long offset) {
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
            throw error("column " + name + " cannot be both NULL and NOT NULL");
        }
        if (auto && !type.type.exactInteger()) {
            throw error("AUTOINCREMENT is only allowed on SMALLINT, INTEGER, or BIGINT");
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
        throw error("DEFAULT accepts only a literal");
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
            int precision = expectInt("precision");
            int scale = 0;
            if (consumeSymbol(",")) {
                scale = expectInt("scale");
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
        throw error("unknown data type");
    }

    private void validateType(String name, TypeSpec type) {
        if ((type.type == DataType.CHAR || type.type == DataType.VARCHAR)
                && (type.length < 1 || type.length > Format.MAX_CHAR)) {
            throw error("size of " + name + " must be between 1 and " + Format.MAX_CHAR);
        }
        if (type.type == DataType.DECIMAL && (type.length < 1 || type.length > 38 || type.scale < 0 || type.scale > type.length)) {
            throw error("invalid DECIMAL in " + name + " (precision 1..38 and scale up to the precision)");
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
                throw error("IN requires at least one value");
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
            throw error("NOT must accompany LIKE, IN, or BETWEEN");
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
            return new NextValue(expectRelation());
        }
        if (token.kind == TokenKind.IDENT && !token.quoted && contextVariable(token.text) && !peekSymbol(".")) {
            String name = token.text.toUpperCase(Locale.ROOT);
            cursor++;
            Integer precision = null;
            if (consumeSymbol("(")) {
                if (!timedContext(name)) {
                    throw error(name + " does not accept arguments");
                }
                precision = expectInt("precision");
                if (precision < 0 || precision > 3) {
                    throw error("precision of " + name + " must be between 0 and 3");
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
        throw error("invalid expression");
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
                throw error("DISTINCT cannot be combined with *");
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
            throw error("the column list is empty");
        }
        return names;
    }

    private int expectLength() {
        expectSymbol("(");
        int length = expectInt("size");
        expectSymbol(")");
        return length;
    }

    private int expectInt(String label) {
        long value = expectLong(label);
        if (value > Integer.MAX_VALUE) {
            throw error(label + " is too large");
        }
        return (int) value;
    }

    private long expectLong(String label) {
        Token token = current();
        if (token.kind != TokenKind.NUMBER) {
            throw error("expected a number in " + label);
        }
        cursor++;
        BigDecimal number = new BigDecimal(token.text);
        try {
            return number.longValueExact();
        } catch (ArithmeticException exception) {
            throw error(label + " must be an integer");
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
            throw error("expected an identifier");
        }
        Names.checkName(token.text, "identifier");
        cursor++;
        return token.text;
    }

    private Token expectNumber() {
        Token token = current();
        if (token.kind != TokenKind.NUMBER) {
            throw error("expected a number");
        }
        cursor++;
        return token;
    }

    private void expectKeyword(String keyword) {
        if (!consumeKeyword(keyword)) {
            throw error("expected " + keyword);
        }
    }

    private void expectSymbol(String symbol) {
        if (!consumeSymbol(symbol)) {
            throw error("expected '" + symbol + "'");
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
                || peekKeyword("JOIN") || peekKeyword("INNER") || peekKeyword("LEFT") || peekKeyword("RIGHT")
                || peekKeyword("FULL") || peekKeyword("CROSS") || peekKeyword("OUTER") || peekKeyword("NATURAL")
                || peekKeyword("USING") || peekKeyword("ON")
                || peekKeyword("SET") || peekKeyword("INTO") || peekKeyword("DO");
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
        return new NexException(message + " near '" + near + "' (position " + (position + 1) + ")");
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
                    throw new NexException("unterminated comment");
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
                    throw new NexException("unterminated string (position " + (start + 1) + ")");
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
                    throw new NexException("unterminated hexadecimal literal");
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
                    throw new NexException("unterminated identifier (position " + (start + 1) + ")");
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
            throw new NexException("invalid character '" + current + "' (position " + (start + 1) + ")");
        }
        tokens.add(new Token(TokenKind.EOF, "", null, sql.length(), sql.length(), false, -1));
    }

    private static byte[] decodeHex(String hex, int position) {
        if ((hex.length() % 2) != 0) {
            throw new NexException("incomplete hexadecimal literal (position " + (position + 1) + ")");
        }
        byte[] data = new byte[hex.length() / 2];
        for (int i = 0; i < data.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new NexException("invalid hexadecimal digit (position " + (position + 1) + ")");
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
