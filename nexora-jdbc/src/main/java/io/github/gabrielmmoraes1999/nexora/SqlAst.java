package io.github.gabrielmmoraes1999.nexora;

import java.util.List;

record ParsedSql(List<Stmt> statements, int parameters) {
}

sealed interface Stmt permits CreateTable, CreateIndex, DropTable, DropIndex, AddColumn, DropColumn, AddPrimaryKey, Insert, Update, Delete, Select,
        SelectProcedure, Grant, Revoke, BeginTx, CommitTx, RollbackTx, CreateGenerator, DropGenerator, SetGenerator,
        CreateProcedure, DropProcedure, ExecuteProcedure, CreateTrigger, DropTrigger, AlterTrigger, CreateView, DropView,
        CreateRole, DropRole, GrantRole, RevokeRole, DropConnection, CreateSchema, DropSchema, SetSchema {
}

record CreateView(boolean ifNotExists, String name, List<String> columns, String source) implements Stmt {
}

record CreateRole(boolean ifNotExists, String name) implements Stmt {
}

record CreateSchema(boolean ifNotExists, String name) implements Stmt {
}

record DropSchema(boolean ifExists, String name, boolean cascade) implements Stmt {
}

record SetSchema(String name) implements Stmt {
}

record DropRole(boolean ifExists, String name) implements Stmt {
}

record GrantRole(String role, String user, boolean adminOption) implements Stmt {
}

record RevokeRole(String role, String user, boolean adminOptionOnly) implements Stmt {
}

record DropConnection(Expr attachment) implements Stmt {
}

record DropView(boolean ifExists, String name) implements Stmt {
}

record CreateTable(boolean ifNotExists, String name, List<ColDef> columns, List<String> primaryKey, List<List<String>> uniques) implements Stmt {
}

record CreateIndex(boolean unique, boolean ifNotExists, String name, String table, List<String> columns) implements Stmt {
}

record DropTable(boolean ifExists, String name) implements Stmt {
}

record DropIndex(boolean ifExists, String name, String table) implements Stmt {
}

record AddColumn(String table, ColDef column) implements Stmt {
}

record DropColumn(String table, String column) implements Stmt {
}

record AddPrimaryKey(String table, List<String> columns) implements Stmt {
}

record Insert(String table, List<String> columns, List<List<Expr>> rows) implements Stmt {
}

record Update(String table, List<Assignment> sets, Expr where) implements Stmt {
}

record Delete(String table, Expr where) implements Stmt {
}

record Grant(List<String> privileges, String table, String user, boolean grantOption) implements Stmt {
}

record Revoke(List<String> privileges, String table, String user) implements Stmt {
}

record Select(boolean distinct, boolean star, List<SelectItem> items, String table, String alias, Expr where,
              List<OrderItem> orderBy, Long limit, Long offset) implements Stmt {
}

record BeginTx() implements Stmt {
}

record CommitTx() implements Stmt {
}

record RollbackTx() implements Stmt {
}

record ColDef(String name, DataType type, int length, int scale, boolean notNull, boolean primaryKey, boolean unique,
               boolean autoIncrement, boolean hasDefault, Object defaultValue) {
}

record Assignment(String column, Expr expr) {
}

record SelectItem(Expr expr, String alias, String label) {
}

record OrderItem(Expr expr, boolean descending) {
}

enum UnaryOp {
    NOT, NEGATE, PLUS
}

enum BinaryOp {
    OR, AND, EQ, NE, LT, GT, LE, GE, CONCAT, ADD, SUB, MUL, DIV, MOD
}

sealed interface Expr permits Literal, Param, ColumnRef, VarRef, NextValue, ContextRef, Unary, Binary, IsNull, Like, InList, Between, Call {
}

record Literal(Object value) implements Expr {
}

record Param(int index) implements Expr {
}

record ColumnRef(String qualifier, String name) implements Expr {
}

record Unary(UnaryOp op, Expr expr) implements Expr {
}

record Binary(BinaryOp op, Expr left, Expr right) implements Expr {
}

record IsNull(Expr expr, boolean negated) implements Expr {
}

record Like(Expr expr, Expr pattern, Expr escape, boolean negated) implements Expr {
}

record InList(Expr expr, List<Expr> values, boolean negated) implements Expr {
}

record Between(Expr expr, Expr low, Expr high, boolean negated) implements Expr {
}

record Call(String name, boolean distinct, boolean star, List<Expr> args) implements Expr {
}

record VarRef(String name) implements Expr {
}

record NextValue(String name) implements Expr {
}

record ContextRef(String name, Integer precision) implements Expr {
}

record ParamDef(String name, DataType type, int length, int scale) {
}

record CreateGenerator(String name, Long start, Long increment, boolean sequence) implements Stmt {
}

record DropGenerator(boolean ifExists, String name) implements Stmt {
}

record SetGenerator(String name, Expr value, boolean restart) implements Stmt {
}

record CreateProcedure(String name, List<ParamDef> inputs, List<ParamDef> outputs, String source) implements Stmt {
}

record DropProcedure(boolean ifExists, String name) implements Stmt {
}

record ExecuteProcedure(String name, List<Expr> arguments, List<String> returning) implements Stmt {
}

record CreateTrigger(String name, String table, boolean inactive, String phase, List<String> events, int position, String source) implements Stmt {
}

record DropTrigger(boolean ifExists, String name) implements Stmt {
}

record AlterTrigger(String name, boolean inactive) implements Stmt {
}

record SelectProcedure(boolean distinct, boolean star, List<SelectItem> items, String procedure, List<Expr> arguments, String alias,
                       Expr where, List<OrderItem> orderBy, Long limit, Long offset) implements Stmt {
}

record CursorDef(String name, Select query) {
}

record Routine(List<ParamDef> locals, List<CursorDef> cursors, Psql body) {
}

sealed interface Psql permits PsqlBlock, PsqlAssign, PsqlIf, PsqlWhile, PsqlExit, PsqlSuspend, PsqlException, PsqlInsert, PsqlUpdate,
        PsqlDelete, PsqlExecute, PsqlSelectInto, PsqlOpen, PsqlFetch, PsqlClose, PsqlForSelect {
}

record PsqlBlock(List<Psql> statements) implements Psql {
}

record PsqlAssign(String qualifier, String name, Expr value) implements Psql {
}

record PsqlIf(Expr condition, Psql thenBranch, Psql elseBranch) implements Psql {
}

record PsqlWhile(Expr condition, Psql body) implements Psql {
}

record PsqlExit() implements Psql {
}

record PsqlSuspend() implements Psql {
}

record PsqlException(Expr message) implements Psql {
}

record PsqlInsert(Insert statement) implements Psql {
}

record PsqlUpdate(Update statement) implements Psql {
}

record PsqlDelete(Delete statement) implements Psql {
}

record PsqlExecute(ExecuteProcedure call) implements Psql {
}

record PsqlSelectInto(List<Expr> expressions, List<String> targets, String table, Expr where) implements Psql {
}

record PsqlOpen(String cursor) implements Psql {
}

record PsqlFetch(String cursor, List<String> targets) implements Psql {
}

record PsqlClose(String cursor) implements Psql {
}

record PsqlForSelect(Select query, List<String> targets, Psql body) implements Psql {
}
