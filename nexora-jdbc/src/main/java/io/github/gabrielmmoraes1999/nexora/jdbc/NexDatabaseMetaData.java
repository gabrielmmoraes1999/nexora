package io.github.gabrielmmoraes1999.nexora.jdbc;

import io.github.gabrielmmoraes1999.nexora.DataType;
import io.github.gabrielmmoraes1999.nexora.ReferentialAction;
import io.github.gabrielmmoraes1999.nexora.Row;
import io.github.gabrielmmoraes1999.nexora.SqlResult;
import io.github.gabrielmmoraes1999.nexora.TableDescription;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class NexDatabaseMetaData implements DatabaseMetaData {

    private final NexConnection connection;

    NexDatabaseMetaData(NexConnection connection) {
        this.connection = connection;
    }

    @Override
    public boolean allProceduresAreCallable() {
        return false;
    }

    @Override
    public boolean allTablesAreSelectable() {
        return true;
    }

    @Override
    public String getURL() {
        return connection.url();
    }

    @Override
    public String getUserName() {
        return connection.user();
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return connection.isReadOnly();
    }

    @Override
    public boolean nullsAreSortedHigh() {
        return true;
    }

    @Override
    public boolean nullsAreSortedLow() {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtStart() {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtEnd() {
        return false;
    }

    @Override
    public String getDatabaseProductName() {
        return "Nexora";
    }

    @Override
    public String getDatabaseProductVersion() {
        return "1.0";
    }

    @Override
    public String getDriverName() {
        return "Nexora JDBC";
    }

    @Override
    public String getDriverVersion() {
        return "1.0";
    }

    @Override
    public int getDriverMajorVersion() {
        return 1;
    }

    @Override
    public int getDriverMinorVersion() {
        return 0;
    }

    @Override
    public boolean usesLocalFiles() {
        return true;
    }

    @Override
    public boolean usesLocalFilePerTable() {
        return true;
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesUpperCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesLowerCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesMixedCaseIdentifiers() {
        return true;
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() {
        return true;
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() {
        return true;
    }

    @Override
    public String getIdentifierQuoteString() {
        return "\"";
    }

    @Override
    public String getSQLKeywords() {
        return "AUTOINCREMENT,LIMIT,OFFSET";
    }

    @Override
    public String getNumericFunctions() {
        return "";
    }

    @Override
    public String getStringFunctions() {
        return "LOWER,UPPER,LENGTH,CHAR_LENGTH,COALESCE";
    }

    @Override
    public String getSystemFunctions() {
        return "";
    }

    @Override
    public String getTimeDateFunctions() {
        return "";
    }

    @Override
    public String getSearchStringEscape() {
        return "\\";
    }

    @Override
    public String getExtraNameCharacters() {
        return "";
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() {
        return true;
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() {
        return true;
    }

    @Override
    public boolean supportsColumnAliasing() {
        return true;
    }

    @Override
    public boolean nullPlusNonNullIsNull() {
        return true;
    }

    @Override
    public boolean supportsConvert() {
        return false;
    }

    @Override
    public boolean supportsConvert(int fromType, int toType) {
        return false;
    }

    @Override
    public boolean supportsTableCorrelationNames() {
        return true;
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() {
        return false;
    }

    @Override
    public boolean supportsExpressionsInOrderBy() {
        return true;
    }

    @Override
    public boolean supportsOrderByUnrelated() {
        return true;
    }

    @Override
    public boolean supportsGroupBy() {
        return false;
    }

    @Override
    public boolean supportsGroupByUnrelated() {
        return false;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() {
        return false;
    }

    @Override
    public boolean supportsLikeEscapeClause() {
        return true;
    }

    @Override
    public boolean supportsMultipleResultSets() {
        return false;
    }

    @Override
    public boolean supportsMultipleTransactions() {
        return false;
    }

    @Override
    public boolean supportsNonNullableColumns() {
        return true;
    }

    @Override
    public boolean supportsMinimumSQLGrammar() {
        return true;
    }

    @Override
    public boolean supportsCoreSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsExtendedSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() {
        return false;
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() {
        return false;
    }

    @Override
    public boolean supportsANSI92FullSQL() {
        return false;
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() {
        return true;
    }

    @Override
    public boolean supportsOuterJoins() {
        return false;
    }

    @Override
    public boolean supportsFullOuterJoins() {
        return false;
    }

    @Override
    public boolean supportsLimitedOuterJoins() {
        return false;
    }

    @Override
    public String getSchemaTerm() {
        return "schema";
    }

    @Override
    public String getProcedureTerm() {
        return "";
    }

    @Override
    public String getCatalogTerm() {
        return "";
    }

    @Override
    public boolean isCatalogAtStart() {
        return true;
    }

    @Override
    public String getCatalogSeparator() {
        return "";
    }

    @Override
    public boolean supportsSchemasInDataManipulation() {
        return true;
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() {
        return false;
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() {
        return true;
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() {
        return true;
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() {
        return true;
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() {
        return false;
    }

    @Override
    public boolean supportsPositionedDelete() {
        return false;
    }

    @Override
    public boolean supportsPositionedUpdate() {
        return false;
    }

    @Override
    public boolean supportsSelectForUpdate() {
        return false;
    }

    @Override
    public boolean supportsStoredProcedures() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInComparisons() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInExists() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInIns() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() {
        return false;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() {
        return false;
    }

    @Override
    public boolean supportsUnion() {
        return false;
    }

    @Override
    public boolean supportsUnionAll() {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() {
        return true;
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() {
        return true;
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() {
        return true;
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() {
        return true;
    }

    @Override
    public int getMaxBinaryLiteralLength() {
        return 0;
    }

    @Override
    public int getMaxCharLiteralLength() {
        return 0;
    }

    @Override
    public int getMaxColumnNameLength() {
        return 128;
    }

    @Override
    public int getMaxColumnsInGroupBy() {
        return 0;
    }

    @Override
    public int getMaxColumnsInIndex() {
        return 0;
    }

    @Override
    public int getMaxColumnsInOrderBy() {
        return 0;
    }

    @Override
    public int getMaxColumnsInSelect() {
        return 0;
    }

    @Override
    public int getMaxColumnsInTable() {
        return 1024;
    }

    @Override
    public int getMaxConnections() {
        return 0;
    }

    @Override
    public int getMaxCursorNameLength() {
        return 0;
    }

    @Override
    public int getMaxIndexLength() {
        return 0;
    }

    @Override
    public int getMaxSchemaNameLength() {
        return 0;
    }

    @Override
    public int getMaxProcedureNameLength() {
        return 0;
    }

    @Override
    public int getMaxCatalogNameLength() {
        return 0;
    }

    @Override
    public int getMaxRowSize() {
        return 0;
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() {
        return false;
    }

    @Override
    public int getMaxStatementLength() {
        return 0;
    }

    @Override
    public int getMaxStatements() {
        return 0;
    }

    @Override
    public int getMaxTableNameLength() {
        return 128;
    }

    @Override
    public int getMaxTablesInSelect() {
        return 1;
    }

    @Override
    public int getMaxUserNameLength() {
        return 0;
    }

    @Override
    public int getDefaultTransactionIsolation() {
        return Connection.TRANSACTION_SERIALIZABLE;
    }

    @Override
    public boolean supportsTransactions() {
        return true;
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int level) {
        return level == Connection.TRANSACTION_READ_UNCOMMITTED
                || level == Connection.TRANSACTION_READ_COMMITTED
                || level == Connection.TRANSACTION_REPEATABLE_READ
                || level == Connection.TRANSACTION_SERIALIZABLE;
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() {
        return false;
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() {
        return true;
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() {
        return false;
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() {
        return false;
    }

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) throws SQLException {
        List<JdbcColumn> columns = List.of(
                JdbcColumn.text("PROCEDURE_CAT"),
                JdbcColumn.text("PROCEDURE_SCHEM"),
                JdbcColumn.text("PROCEDURE_NAME"),
                JdbcColumn.text("RESERVED1"),
                JdbcColumn.text("RESERVED2"),
                JdbcColumn.text("RESERVED3"),
                JdbcColumn.text("REMARKS"),
                JdbcColumn.smallint("PROCEDURE_TYPE"),
                JdbcColumn.text("SPECIFIC_NAME"));
        if (!open(catalog) || !proceduresVisible(schemaPattern)) {
            return grid(columns, List.of());
        }
        Set<String> returns = new HashSet<>();
        for (Row row : catalogQuery("""
                SELECT NEX$SCHEMA_NAME, NEX$PROCEDURE_NAME, NEX$PARAMETER_TYPE
                FROM NEX$PROCEDURE_PARAMETERS
                """).rows()) {
            if (integer(row, "NEX$PARAMETER_TYPE") == 1) {
                returns.add(procedureKey(row.getString("NEX$SCHEMA_NAME"), row.getString("NEX$PROCEDURE_NAME")));
            }
        }
        List<Object[]> rows = new ArrayList<>();
        for (Row row : catalogQuery("SELECT NEX$SCHEMA_NAME, NEX$PROCEDURE_NAME FROM NEX$PROCEDURES").rows()) {
            String schema = row.getString("NEX$SCHEMA_NAME");
            String name = row.getString("NEX$PROCEDURE_NAME");
            if (!matchesSchema(schemaPattern, schema) || !Jdbc.nameMatches(procedureNamePattern, name)) {
                continue;
            }
            short type = returns.contains(procedureKey(schema, name))
                    ? (short) procedureReturnsResult : (short) procedureNoResult;
            rows.add(new Object[]{null, schema, name, null, null, null, null, type, name});
        }
        rows.sort(Comparator
                .comparing((Object[] row) -> (String) row[1], String.CASE_INSENSITIVE_ORDER)
                .thenComparing(row -> (String) row[2], String.CASE_INSENSITIVE_ORDER));
        return grid(columns, rows);
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern)
            throws SQLException {
        List<JdbcColumn> columns = List.of(
                JdbcColumn.text("PROCEDURE_CAT"),
                JdbcColumn.text("PROCEDURE_SCHEM"),
                JdbcColumn.text("PROCEDURE_NAME"),
                JdbcColumn.text("COLUMN_NAME"),
                JdbcColumn.smallint("COLUMN_TYPE"),
                JdbcColumn.integer("DATA_TYPE"),
                JdbcColumn.text("TYPE_NAME"),
                JdbcColumn.integer("PRECISION"),
                JdbcColumn.integer("LENGTH"),
                JdbcColumn.smallint("SCALE"),
                JdbcColumn.smallint("RADIX"),
                JdbcColumn.smallint("NULLABLE"),
                JdbcColumn.text("REMARKS"),
                JdbcColumn.text("COLUMN_DEF"),
                JdbcColumn.integer("SQL_DATA_TYPE"),
                JdbcColumn.integer("SQL_DATETIME_SUB"),
                JdbcColumn.integer("CHAR_OCTET_LENGTH"),
                JdbcColumn.integer("ORDINAL_POSITION"),
                JdbcColumn.text("IS_NULLABLE"),
                JdbcColumn.text("SPECIFIC_NAME"));
        if (!open(catalog) || !proceduresVisible(schemaPattern)) {
            return grid(columns, List.of());
        }
        List<ProcedureParameter> parameters = new ArrayList<>();
        for (Row row : catalogQuery("""
                SELECT NEX$SCHEMA_NAME, NEX$PROCEDURE_NAME, NEX$PARAMETER_NAME, NEX$PARAMETER_NUMBER, NEX$PARAMETER_TYPE,
                       NEX$FIELD_TYPE, NEX$FIELD_LENGTH, NEX$FIELD_SCALE
                FROM NEX$PROCEDURE_PARAMETERS
                """).rows()) {
            parameters.add(new ProcedureParameter(
                    row.getString("NEX$SCHEMA_NAME"),
                    row.getString("NEX$PROCEDURE_NAME"),
                    row.getString("NEX$PARAMETER_NAME"),
                    integer(row, "NEX$PARAMETER_TYPE"),
                    integer(row, "NEX$PARAMETER_NUMBER"),
                    DataType.valueOf(row.getString("NEX$FIELD_TYPE")),
                    integer(row, "NEX$FIELD_LENGTH"),
                    integer(row, "NEX$FIELD_SCALE")));
        }
        parameters.sort(Comparator
                .comparing(ProcedureParameter::schema, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ProcedureParameter::procedure, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(ProcedureParameter::direction)
                .thenComparingInt(ProcedureParameter::number));
        List<Object[]> rows = new ArrayList<>();
        String currentSchema = null;
        String current = null;
        int ordinal = 0;
        for (ProcedureParameter parameter : parameters) {
            if (current == null || !current.equalsIgnoreCase(parameter.procedure())
                    || currentSchema == null || !currentSchema.equalsIgnoreCase(parameter.schema())) {
                currentSchema = parameter.schema();
                current = parameter.procedure();
                ordinal = 1;
            } else {
                ordinal++;
            }
            if (!matchesSchema(schemaPattern, parameter.schema())
                    || !Jdbc.nameMatches(procedureNamePattern, parameter.procedure())
                    || !Jdbc.nameMatches(columnNamePattern, parameter.name())) {
                continue;
            }
            DataType type = parameter.type();
            Integer digits = Jdbc.decimalDigits(type, parameter.scale());
            Integer radix = Jdbc.radix(type);
            short columnType = parameter.direction() == 0 ? (short) procedureColumnIn : (short) procedureColumnOut;
            rows.add(new Object[]{
                    null,
                    parameter.schema(),
                    parameter.procedure(),
                    parameter.name(),
                    columnType,
                    Jdbc.sqlType(type),
                    type.name(),
                    Jdbc.precision(type, parameter.length()),
                    Jdbc.precision(type, parameter.length()),
                    digits == null ? null : digits.shortValue(),
                    radix == null ? null : radix.shortValue(),
                    (short) procedureNullable,
                    null,
                    null,
                    null,
                    null,
                    Jdbc.octetLength(type, parameter.length()),
                    ordinal,
                    "YES",
                    parameter.procedure()
            });
        }
        return grid(columns, rows);
    }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) throws SQLException {
        List<JdbcColumn> columns = List.of(
                JdbcColumn.text("TABLE_CAT"),
                JdbcColumn.text("TABLE_SCHEM"),
                JdbcColumn.text("TABLE_NAME"),
                JdbcColumn.text("TABLE_TYPE"),
                JdbcColumn.text("REMARKS"),
                JdbcColumn.text("TYPE_CAT"),
                JdbcColumn.text("TYPE_SCHEM"),
                JdbcColumn.text("TYPE_NAME"),
                JdbcColumn.text("SELF_REFERENCING_COL_NAME"),
                JdbcColumn.text("REF_GENERATION"));
        if (!open(catalog)) {
            return grid(columns, List.of());
        }
        List<Object[]> rows = new ArrayList<>();
        for (TableDescription table : descriptions()) {
            boolean system = table.name().regionMatches(true, 0, "NEX$", 0, 4);
            String type = system ? "SYSTEM TABLE" : "TABLE";
            if (!matchesSchema(schemaPattern, table.schema())
                    || !wants(types, type) || !Jdbc.nameMatches(tableNamePattern, table.name())) {
                continue;
            }
            rows.add(new Object[]{null, table.schema(), table.name(), type, null, null, null, null, null, null});
        }
        for (TableDescription view : views()) {
            if (!matchesSchema(schemaPattern, view.schema())
                    || !wants(types, "VIEW") || !Jdbc.nameMatches(tableNamePattern, view.name())) {
                continue;
            }
            rows.add(new Object[]{null, view.schema(), view.name(), "VIEW", null, null, null, null, null, null});
        }
        return grid(columns, rows);
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        return getSchemas(null, null);
    }

    @Override
    public ResultSet getCatalogs() {
        return empty(JdbcColumn.text("TABLE_CAT"));
    }

    @Override
    public ResultSet getTableTypes() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"TABLE"});
        rows.add(new Object[]{"VIEW"});
        rows.add(new Object[]{"SYSTEM TABLE"});
        return grid(List.of(JdbcColumn.text("TABLE_TYPE")), rows);
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern)
            throws SQLException {
        List<JdbcColumn> columns = List.of(
                JdbcColumn.text("TABLE_CAT"),
                JdbcColumn.text("TABLE_SCHEM"),
                JdbcColumn.text("TABLE_NAME"),
                JdbcColumn.text("COLUMN_NAME"),
                JdbcColumn.integer("DATA_TYPE"),
                JdbcColumn.text("TYPE_NAME"),
                JdbcColumn.integer("COLUMN_SIZE"),
                JdbcColumn.integer("BUFFER_LENGTH"),
                JdbcColumn.integer("DECIMAL_DIGITS"),
                JdbcColumn.integer("NUM_PREC_RADIX"),
                JdbcColumn.integer("NULLABLE"),
                JdbcColumn.text("REMARKS"),
                JdbcColumn.text("COLUMN_DEF"),
                JdbcColumn.integer("SQL_DATA_TYPE"),
                JdbcColumn.integer("SQL_DATETIME_SUB"),
                JdbcColumn.integer("CHAR_OCTET_LENGTH"),
                JdbcColumn.integer("ORDINAL_POSITION"),
                JdbcColumn.text("IS_NULLABLE"),
                JdbcColumn.text("SCOPE_CATALOG"),
                JdbcColumn.text("SCOPE_SCHEMA"),
                JdbcColumn.text("SCOPE_TABLE"),
                JdbcColumn.smallint("SOURCE_DATA_TYPE"),
                JdbcColumn.text("IS_AUTOINCREMENT"),
                JdbcColumn.text("IS_GENERATEDCOLUMN"));
        if (!open(catalog)) {
            return grid(columns, List.of());
        }
        List<Object[]> rows = new ArrayList<>();
        List<TableDescription> relations = new ArrayList<>(descriptions());
        relations.addAll(views());
        for (TableDescription table : relations) {
            if (!matchesSchema(schemaPattern, table.schema()) || !Jdbc.nameMatches(tableNamePattern, table.name())) {
                continue;
            }
            int position = 1;
            for (TableDescription.TableColumn column : table.columns()) {
                if (!Jdbc.nameMatches(columnNamePattern, column.name())) {
                    position++;
                    continue;
                }
                rows.add(new Object[]{
                        null,
                        table.schema(),
                        column.table(),
                        column.name(),
                        Jdbc.sqlType(column.type()),
                        column.type().name(),
                        Jdbc.precision(column.type(), column.length()),
                        null,
                        Jdbc.decimalDigits(column.type(), column.scale()),
                        Jdbc.radix(column.type()),
                        column.nullable() ? columnNullable : columnNoNulls,
                        null,
                        column.defaultSql(),
                        null,
                        null,
                        Jdbc.octetLength(column.type(), column.length()),
                        position,
                        column.nullable() ? "YES" : "NO",
                        null,
                        null,
                        null,
                        null,
                        column.autoIncrement() ? "YES" : "NO",
                        "NO"
                });
                position++;
            }
        }
        return grid(columns, rows);
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern) {
        return empty(
                JdbcColumn.text("TABLE_CAT"),
                JdbcColumn.text("TABLE_SCHEM"),
                JdbcColumn.text("TABLE_NAME"),
                JdbcColumn.text("COLUMN_NAME"),
                JdbcColumn.text("GRANTOR"),
                JdbcColumn.text("GRANTEE"),
                JdbcColumn.text("PRIVILEGE"),
                JdbcColumn.text("IS_GRANTABLE"));
    }

    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) {
        return empty(
                JdbcColumn.text("TABLE_CAT"),
                JdbcColumn.text("TABLE_SCHEM"),
                JdbcColumn.text("TABLE_NAME"),
                JdbcColumn.text("GRANTOR"),
                JdbcColumn.text("GRANTEE"),
                JdbcColumn.text("PRIVILEGE"),
                JdbcColumn.text("IS_GRANTABLE"));
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable)
            throws SQLException {
        List<JdbcColumn> columns = identifierColumns();
        if (!open(catalog)) {
            return grid(columns, List.of());
        }
        List<Object[]> rows = new ArrayList<>();
        for (TableDescription found : find(schema, table)) {
            for (TableDescription.TableColumn column : found.columns()) {
                if (column.primaryKeyPosition() == 0) {
                    continue;
                }
                rows.add(identifier(column, (short) bestRowSession, (short) bestRowNotPseudo));
            }
        }
        return grid(columns, rows);
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table) {
        return grid(identifierColumns(), List.of());
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        List<JdbcColumn> columns = List.of(
                JdbcColumn.text("TABLE_CAT"),
                JdbcColumn.text("TABLE_SCHEM"),
                JdbcColumn.text("TABLE_NAME"),
                JdbcColumn.text("COLUMN_NAME"),
                JdbcColumn.smallint("KEY_SEQ"),
                JdbcColumn.text("PK_NAME"));
        if (!open(catalog)) {
            return grid(columns, List.of());
        }
        List<Object[]> rows = new ArrayList<>();
        for (TableDescription found : find(schema, table)) {
            for (TableDescription.TableColumn column : found.columns()) {
                if (column.primaryKeyPosition() > 0) {
                    rows.add(new Object[]{
                            null, found.schema(), column.table(), column.name(),
                            (short) column.primaryKeyPosition(),
                            found.primaryConstraint().isBlank() ? "PK_" + found.name() : found.primaryConstraint()
                    });
                }
            }
        }
        rows.sort((left, right) -> Short.compare((short) left[4], (short) right[4]));
        return grid(columns, rows);
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        return foreignKeys(schema, table, null, null);
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        return foreignKeys(null, null, schema, table);
    }

    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable,
                                       String foreignCatalog, String foreignSchema, String foreignTable) throws SQLException {
        return foreignKeys(foreignSchema, foreignTable, parentSchema, parentTable);
    }

    @Override
    public ResultSet getTypeInfo() {
        List<JdbcColumn> columns = List.of(
                JdbcColumn.text("TYPE_NAME"),
                JdbcColumn.integer("DATA_TYPE"),
                JdbcColumn.integer("PRECISION"),
                JdbcColumn.text("LITERAL_PREFIX"),
                JdbcColumn.text("LITERAL_SUFFIX"),
                JdbcColumn.text("CREATE_PARAMS"),
                JdbcColumn.smallint("NULLABLE"),
                JdbcColumn.bool("CASE_SENSITIVE"),
                JdbcColumn.smallint("SEARCHABLE"),
                JdbcColumn.bool("UNSIGNED_ATTRIBUTE"),
                JdbcColumn.bool("FIXED_PREC_SCALE"),
                JdbcColumn.bool("AUTO_INCREMENT"),
                JdbcColumn.text("LOCAL_TYPE_NAME"),
                JdbcColumn.smallint("MINIMUM_SCALE"),
                JdbcColumn.smallint("MAXIMUM_SCALE"),
                JdbcColumn.integer("SQL_DATA_TYPE"),
                JdbcColumn.integer("SQL_DATETIME_SUB"),
                JdbcColumn.integer("NUM_PREC_RADIX"));
        List<Object[]> rows = new ArrayList<>();
        addType(rows, DataType.SMALLINT, null, null, null, true);
        addType(rows, DataType.INTEGER, null, null, null, true);
        addType(rows, DataType.BIGINT, null, null, null, true);
        addType(rows, DataType.FLOAT, null, null, null, false);
        addType(rows, DataType.DOUBLE, null, null, null, false);
        addType(rows, DataType.DECIMAL, null, null, "precision,scale", false);
        addType(rows, DataType.BOOLEAN, null, null, null, false);
        addType(rows, DataType.CHAR, "'", "'", "length", false);
        addType(rows, DataType.VARCHAR, "'", "'", "length", false);
        addType(rows, DataType.DATE, "'", "'", null, false);
        addType(rows, DataType.TIME, "'", "'", null, false);
        addType(rows, DataType.TIMESTAMP, "'", "'", null, false);
        addType(rows, DataType.BLOB, "X'", "'", null, false);
        return grid(columns, rows);
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate)
            throws SQLException {
        List<JdbcColumn> columns = List.of(
                JdbcColumn.text("TABLE_CAT"),
                JdbcColumn.text("TABLE_SCHEM"),
                JdbcColumn.text("TABLE_NAME"),
                JdbcColumn.bool("NON_UNIQUE"),
                JdbcColumn.text("INDEX_QUALIFIER"),
                JdbcColumn.text("INDEX_NAME"),
                JdbcColumn.smallint("TYPE"),
                JdbcColumn.smallint("ORDINAL_POSITION"),
                JdbcColumn.text("COLUMN_NAME"),
                JdbcColumn.text("ASC_OR_DESC"),
                JdbcColumn.bigint("CARDINALITY"),
                JdbcColumn.bigint("PAGES"),
                JdbcColumn.text("FILTER_CONDITION"));
        if (!open(catalog)) {
            return grid(columns, List.of());
        }
        List<Object[]> rows = new ArrayList<>();
        for (TableDescription found : find(schema, table)) {
            for (TableDescription.TableIndex index : found.indexes()) {
            if (unique && !index.unique()) {
                continue;
            }
            int position = 1;
            for (String column : index.columns()) {
                rows.add(new Object[]{
                        null,
                        found.schema(),
                        index.table(),
                        !index.unique(),
                        null,
                        index.name(),
                        tableIndexOther,
                        (short) position++,
                        column,
                        index.descending() ? "D" : "A",
                        null,
                        null,
                        null
                });
            }
            }
        }
        return grid(columns, rows);
    }

    @Override
    public boolean supportsResultSetType(int type) {
        return type == ResultSet.TYPE_FORWARD_ONLY || type == ResultSet.TYPE_SCROLL_INSENSITIVE;
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) {
        return supportsResultSetType(type) && concurrency == ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public boolean ownUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean updatesAreDetected(int type) {
        return false;
    }

    @Override
    public boolean deletesAreDetected(int type) {
        return false;
    }

    @Override
    public boolean insertsAreDetected(int type) {
        return false;
    }

    @Override
    public boolean supportsBatchUpdates() {
        return true;
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types) {
        return empty(
                JdbcColumn.text("TYPE_CAT"),
                JdbcColumn.text("TYPE_SCHEM"),
                JdbcColumn.text("TYPE_NAME"),
                JdbcColumn.text("CLASS_NAME"),
                JdbcColumn.integer("DATA_TYPE"),
                JdbcColumn.text("REMARKS"),
                JdbcColumn.smallint("BASE_TYPE"));
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public boolean supportsSavepoints() {
        return false;
    }

    @Override
    public boolean supportsNamedParameters() {
        return false;
    }

    @Override
    public boolean supportsMultipleOpenResults() {
        return false;
    }

    @Override
    public boolean supportsGetGeneratedKeys() {
        return false;
    }

    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) {
        return empty(
                JdbcColumn.text("TYPE_CAT"),
                JdbcColumn.text("TYPE_SCHEM"),
                JdbcColumn.text("TYPE_NAME"),
                JdbcColumn.text("SUPERTYPE_CAT"),
                JdbcColumn.text("SUPERTYPE_SCHEM"),
                JdbcColumn.text("SUPERTYPE_NAME"));
    }

    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) {
        return empty(
                JdbcColumn.text("TABLE_CAT"),
                JdbcColumn.text("TABLE_SCHEM"),
                JdbcColumn.text("TABLE_NAME"),
                JdbcColumn.text("SUPERTABLE_NAME"));
    }

    @Override
    public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern) {
        return empty(
                JdbcColumn.text("TYPE_CAT"),
                JdbcColumn.text("TYPE_SCHEM"),
                JdbcColumn.text("TYPE_NAME"),
                JdbcColumn.text("ATTR_NAME"),
                JdbcColumn.integer("DATA_TYPE"),
                JdbcColumn.text("ATTR_TYPE_NAME"),
                JdbcColumn.integer("ATTR_SIZE"),
                JdbcColumn.integer("DECIMAL_DIGITS"),
                JdbcColumn.integer("NUM_PREC_RADIX"),
                JdbcColumn.integer("NULLABLE"),
                JdbcColumn.text("REMARKS"),
                JdbcColumn.text("ATTR_DEF"),
                JdbcColumn.integer("SQL_DATA_TYPE"),
                JdbcColumn.integer("SQL_DATETIME_SUB"),
                JdbcColumn.integer("CHAR_OCTET_LENGTH"),
                JdbcColumn.integer("ORDINAL_POSITION"),
                JdbcColumn.text("IS_NULLABLE"),
                JdbcColumn.text("SCOPE_CATALOG"),
                JdbcColumn.text("SCOPE_SCHEMA"),
                JdbcColumn.text("SCOPE_TABLE"),
                JdbcColumn.smallint("SOURCE_DATA_TYPE"));
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) {
        return holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT || holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public int getResultSetHoldability() {
        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public int getDatabaseMajorVersion() {
        return 1;
    }

    @Override
    public int getDatabaseMinorVersion() {
        return 0;
    }

    @Override
    public int getJDBCMajorVersion() {
        return 4;
    }

    @Override
    public int getJDBCMinorVersion() {
        return 2;
    }

    @Override
    public int getSQLStateType() {
        return sqlStateSQL;
    }

    @Override
    public boolean locatorsUpdateCopy() {
        return false;
    }

    @Override
    public boolean supportsStatementPooling() {
        return false;
    }

    @Override
    public RowIdLifetime getRowIdLifetime() {
        return RowIdLifetime.ROWID_UNSUPPORTED;
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        if (!open(catalog)) {
            return empty(JdbcColumn.text("TABLE_SCHEM"), JdbcColumn.text("TABLE_CATALOG"));
        }
        connection.checkOpen();
        List<Object[]> rows = new ArrayList<>();
        try {
            for (String schema : connection.schemas()) {
                if (matchesSchema(schemaPattern, schema)) {
                    rows.add(new Object[]{schema, null});
                }
            }
        } catch (RuntimeException exception) {
            throw Jdbc.wrap(exception);
        }
        return grid(List.of(JdbcColumn.text("TABLE_SCHEM"), JdbcColumn.text("TABLE_CATALOG")), rows);
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() {
        return false;
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() {
        return false;
    }

    @Override
    public ResultSet getClientInfoProperties() {
        return empty(
                JdbcColumn.text("NAME"),
                JdbcColumn.integer("MAX_LEN"),
                JdbcColumn.text("DEFAULT_VALUE"),
                JdbcColumn.text("DESCRIPTION"));
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) {
        return empty(
                JdbcColumn.text("FUNCTION_CAT"),
                JdbcColumn.text("FUNCTION_SCHEM"),
                JdbcColumn.text("FUNCTION_NAME"),
                JdbcColumn.text("REMARKS"),
                JdbcColumn.smallint("FUNCTION_TYPE"),
                JdbcColumn.text("SPECIFIC_NAME"));
    }

    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern) {
        return empty(
                JdbcColumn.text("FUNCTION_CAT"),
                JdbcColumn.text("FUNCTION_SCHEM"),
                JdbcColumn.text("FUNCTION_NAME"),
                JdbcColumn.text("COLUMN_NAME"),
                JdbcColumn.smallint("COLUMN_TYPE"),
                JdbcColumn.integer("DATA_TYPE"),
                JdbcColumn.text("TYPE_NAME"),
                JdbcColumn.integer("PRECISION"),
                JdbcColumn.integer("LENGTH"),
                JdbcColumn.smallint("SCALE"),
                JdbcColumn.smallint("RADIX"),
                JdbcColumn.smallint("NULLABLE"),
                JdbcColumn.text("REMARKS"),
                JdbcColumn.integer("CHAR_OCTET_LENGTH"),
                JdbcColumn.integer("ORDINAL_POSITION"),
                JdbcColumn.text("IS_NULLABLE"),
                JdbcColumn.text("SPECIFIC_NAME"));
    }

    @Override
    public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) {
        return empty(
                JdbcColumn.text("TABLE_CAT"),
                JdbcColumn.text("TABLE_SCHEM"),
                JdbcColumn.text("TABLE_NAME"),
                JdbcColumn.text("COLUMN_NAME"),
                JdbcColumn.integer("DATA_TYPE"),
                JdbcColumn.integer("COLUMN_SIZE"),
                JdbcColumn.integer("DECIMAL_DIGITS"),
                JdbcColumn.integer("NUM_PREC_RADIX"),
                JdbcColumn.text("COLUMN_USAGE"),
                JdbcColumn.text("REMARKS"),
                JdbcColumn.integer("CHAR_OCTET_LENGTH"),
                JdbcColumn.text("IS_NULLABLE"));
    }

    @Override
    public boolean generatedKeyAlwaysReturned() {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface != null && iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("is not " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface != null && iface.isInstance(this);
    }

    private List<TableDescription> descriptions() throws SQLException {
        connection.checkOpen();
        try {
            return connection.describeAllTables();
        } catch (RuntimeException exception) {
            throw Jdbc.wrap(exception);
        }
    }

    private List<TableDescription> views() throws SQLException {
        connection.checkOpen();
        try {
            return connection.describeViews();
        } catch (RuntimeException exception) {
            throw Jdbc.wrap(exception);
        }
    }

    private List<TableDescription> find(String schema, String name) throws SQLException {
        if (name == null) {
            return List.of();
        }
        List<TableDescription> found = new ArrayList<>();
        for (TableDescription table : descriptions()) {
            if (table.name().equalsIgnoreCase(name) && matchesSchema(schema, table.schema())) {
                found.add(table);
            }
        }
        return found;
    }

    private boolean proceduresVisible(String schemaPattern) throws SQLException {
        if (schemaPattern == null || schemaPattern.isEmpty() || "%".equals(schemaPattern)) {
            return true;
        }
        for (String schema : connection.schemas()) {
            if (Jdbc.nameMatches(schemaPattern, schema)) {
                return true;
            }
        }
        return false;
    }

    private SqlResult catalogQuery(String sql) throws SQLException {
        synchronized (connection.lock()) {
            connection.checkOpen();
            return connection.execute(sql, new Object[0]);
        }
    }

    private static int integer(Row row, String column) throws SQLException {
        Object value = row.get(column);
        if (!(value instanceof Number number)) {
            throw new SQLException("column " + column + " is not a number", "HY000");
        }
        return number.intValue();
    }

    private static boolean matchesSchema(String pattern, String schema) {
        if (pattern == null || pattern.isEmpty() || "%".equals(pattern)) {
            return true;
        }
        return Jdbc.nameMatches(pattern, schema);
    }

    private static boolean visible(String catalog, String schema) {
        return open(catalog) && matchesSchema(schema, "public");
    }

    private static boolean open(String pattern) {
        return pattern == null || pattern.isEmpty() || "%".equals(pattern);
    }

    private static boolean wants(String[] types, String expected) {
        if (types == null) {
            return true;
        }
        for (String type : types) {
            if (type != null && type.equalsIgnoreCase(expected)) {
                return true;
            }
        }
        return false;
    }

    private static ResultSet empty(JdbcColumn... columns) {
        return grid(List.of(columns), List.of());
    }

    private static ResultSet grid(List<JdbcColumn> columns, List<Object[]> rows) {
        return NexResultSet.metadata(columns, rows);
    }

    private ResultSet foreignKeys(String foreignSchema, String foreignTable, String primarySchema, String primaryTable)
            throws SQLException {
        List<JdbcColumn> columns = List.of(
                JdbcColumn.text("PKTABLE_CAT"),
                JdbcColumn.text("PKTABLE_SCHEM"),
                JdbcColumn.text("PKTABLE_NAME"),
                JdbcColumn.text("PKCOLUMN_NAME"),
                JdbcColumn.text("FKTABLE_CAT"),
                JdbcColumn.text("FKTABLE_SCHEM"),
                JdbcColumn.text("FKTABLE_NAME"),
                JdbcColumn.text("FKCOLUMN_NAME"),
                JdbcColumn.smallint("KEY_SEQ"),
                JdbcColumn.smallint("UPDATE_RULE"),
                JdbcColumn.smallint("DELETE_RULE"),
                JdbcColumn.text("FK_NAME"),
                JdbcColumn.text("PK_NAME"),
                JdbcColumn.smallint("DEFERRABILITY"));
        if (foreignTable == null && primaryTable == null) {
            return grid(columns, List.of());
        }
        List<TableDescription> described = descriptions();
        List<Object[]> rows = new ArrayList<>();
        for (TableDescription child : described) {
            if (foreignTable != null && (!child.name().equalsIgnoreCase(foreignTable) || !matchesSchema(foreignSchema, child.schema()))) {
                continue;
            }
            for (TableDescription.ForeignKey key : child.foreignKeys()) {
                if (primaryTable != null && (!key.referencedTable().equalsIgnoreCase(primaryTable)
                        || !matchesSchema(primarySchema, key.referencedSchema()))) {
                    continue;
                }
                String parentKey = parentKeyName(described, key);
                for (int i = 0; i < key.columns().size(); i++) {
                    String referenced = i < key.referencedColumns().size() ? key.referencedColumns().get(i) : null;
                    rows.add(new Object[]{
                            null,
                            key.referencedSchema(),
                            key.referencedTable(),
                            referenced,
                            null,
                            child.schema(),
                            child.name(),
                            key.columns().get(i),
                            (short) (i + 1),
                            ruleCode(key.onUpdate()),
                            ruleCode(key.onDelete()),
                            key.name(),
                            parentKey,
                            (short) importedKeyNotDeferrable
                    });
                }
            }
        }
        return grid(columns, rows);
    }

    private static short ruleCode(ReferentialAction action) {
        return switch (action) {
            case CASCADE -> (short) importedKeyCascade;
            case RESTRICT -> (short) importedKeyRestrict;
            case SET_NULL -> (short) importedKeySetNull;
            case SET_DEFAULT -> (short) importedKeySetDefault;
            case NO_ACTION -> (short) importedKeyNoAction;
        };
    }

    private static String parentKeyName(List<TableDescription> described, TableDescription.ForeignKey key) {
        for (TableDescription parent : described) {
            if (!parent.name().equalsIgnoreCase(key.referencedTable()) || !parent.schema().equalsIgnoreCase(key.referencedSchema())) {
                continue;
            }
            if (sameNames(parentKeyColumns(parent), key.referencedColumns())) {
                return parent.primaryConstraint().isBlank() ? "PK_" + parent.name() : parent.primaryConstraint();
            }
            for (TableDescription.TableIndex index : parent.indexes()) {
                if (index.unique() && sameNames(index.columns(), key.referencedColumns())) {
                    return index.name();
                }
            }
            return parent.primaryConstraint();
        }
        return "PK_" + key.referencedTable();
    }

    private static List<String> parentKeyColumns(TableDescription parent) {
        List<String> names = new ArrayList<>();
        for (TableDescription.TableColumn column : parent.columns()) {
            if (column.primaryKeyPosition() > 0) {
                while (names.size() < column.primaryKeyPosition()) {
                    names.add("");
                }
                names.set(column.primaryKeyPosition() - 1, column.name());
            }
        }
        names.removeIf(String::isBlank);
        return names;
    }

    private static boolean sameNames(List<String> left, List<String> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!left.get(i).equalsIgnoreCase(right.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static ResultSet keys() {
        return empty(
                JdbcColumn.text("PKTABLE_CAT"),
                JdbcColumn.text("PKTABLE_SCHEM"),
                JdbcColumn.text("PKTABLE_NAME"),
                JdbcColumn.text("PKCOLUMN_NAME"),
                JdbcColumn.text("FKTABLE_CAT"),
                JdbcColumn.text("FKTABLE_SCHEM"),
                JdbcColumn.text("FKTABLE_NAME"),
                JdbcColumn.text("FKCOLUMN_NAME"),
                JdbcColumn.smallint("KEY_SEQ"),
                JdbcColumn.smallint("UPDATE_RULE"),
                JdbcColumn.smallint("DELETE_RULE"),
                JdbcColumn.text("FK_NAME"),
                JdbcColumn.text("PK_NAME"),
                JdbcColumn.smallint("DEFERRABILITY"));
    }

    private static List<JdbcColumn> identifierColumns() {
        return List.of(
                JdbcColumn.smallint("SCOPE"),
                JdbcColumn.text("COLUMN_NAME"),
                JdbcColumn.integer("DATA_TYPE"),
                JdbcColumn.text("TYPE_NAME"),
                JdbcColumn.integer("COLUMN_SIZE"),
                JdbcColumn.integer("BUFFER_LENGTH"),
                JdbcColumn.smallint("DECIMAL_DIGITS"),
                JdbcColumn.smallint("PSEUDO_COLUMN"));
    }

    private static Object[] identifier(TableDescription.TableColumn column, short scope, short pseudo) {
        Integer digits = Jdbc.decimalDigits(column.type(), column.scale());
        return new Object[]{
                scope,
                column.name(),
                Jdbc.sqlType(column.type()),
                column.type().name(),
                Jdbc.precision(column.type(), column.length()),
                null,
                digits == null ? null : digits.shortValue(),
                pseudo
        };
    }

    private static void addType(List<Object[]> rows, DataType type, String prefix, String suffix, String params, boolean auto) {
        boolean text = type == DataType.CHAR || type == DataType.VARCHAR;
        short maxScale = (short) (type == DataType.DECIMAL ? 38 : 0);
        rows.add(new Object[]{
                type.name(),
                Jdbc.sqlType(type),
                Jdbc.precision(type, type == DataType.CHAR || type == DataType.VARCHAR ? 0 : 0),
                prefix,
                suffix,
                params,
                (short) typeNullable,
                text,
                (short) typeSearchable,
                false,
                false,
                auto,
                type.name(),
                (short) 0,
                maxScale,
                null,
                null,
                Jdbc.radix(type)
        });
    }

    private static String procedureKey(String schema, String name) {
        String schemaName = schema == null ? "" : schema.toLowerCase(Locale.ROOT);
        String procedureName = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return schemaName + "\0" + procedureName;
    }

    private record ProcedureParameter(
            String schema, String procedure, String name, int direction, int number, DataType type, int length, int scale) {
    }
}
