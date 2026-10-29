package io.github.gabrielmmoraes1999.seadb.studio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Suggestions for the SQL editor. The list depends on the clause under the caret
 * and on the catalog snapshot the editor already has.
 */
final class Completion {

    private static final int LIMIT = 80;

    private static final List<String> STATEMENTS = List.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP",
            "GRANT", "REVOKE", "SET", "EXECUTE", "BEGIN", "START", "COMMIT", "ROLLBACK");
    private static final List<String> CREATE_KINDS = List.of(
            "TABLE", "VIEW", "INDEX", "UNIQUE", "PROCEDURE", "TRIGGER",
            "GENERATOR", "SEQUENCE", "SCHEMA", "ROLE");
    private static final List<String> DROP_KINDS = List.of(
            "TABLE", "VIEW", "INDEX", "PROCEDURE", "TRIGGER", "GENERATOR",
            "SEQUENCE", "SCHEMA", "ROLE", "CONNECTION");
    private static final List<String> ALTER_KINDS = List.of("TABLE", "TRIGGER", "GENERATOR", "SEQUENCE");
    private static final List<String> FUNCTIONS = List.of(
            "COUNT", "SUM", "AVG", "MIN", "MAX", "LOWER", "UPPER", "LENGTH", "CHAR_LENGTH", "COALESCE", "GEN_ID");
    private static final List<String> CONTEXTS = List.of(
            "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER", "CURRENT_ROLE",
            "CURRENT_CONNECTION", "CURRENT_TRANSACTION", "LOCALTIME", "LOCALTIMESTAMP", "USER");
    private static final List<String> TYPES = List.of(
            "SMALLINT", "INTEGER", "BIGINT", "FLOAT", "DOUBLE PRECISION", "DECIMAL", "NUMERIC",
            "BOOLEAN", "CHAR", "VARCHAR", "DATE", "TIME", "TIMESTAMP", "BLOB");
    private static final List<String> TYPE_WORDS = List.of(
            "SMALLINT", "INTEGER", "INT", "BIGINT", "FLOAT", "DOUBLE", "PRECISION", "DECIMAL", "NUMERIC",
            "BOOLEAN", "BOOL", "CHAR", "CHARACTER", "VARCHAR", "VARYING", "DATE", "TIME", "TIMESTAMP", "BLOB");
    private static final List<String> SELECT_WORDS = List.of(
            "DISTINCT", "FROM", "AS", "WHERE", "AND", "OR", "NOT", "ORDER", "ASC", "DESC",
            "LIMIT", "OFFSET", "IS", "NULL", "IN", "LIKE", "BETWEEN", "TRUE", "FALSE");
    private static final List<String> FILTER_WORDS = List.of(
            "AND", "OR", "NOT", "ORDER", "ASC", "DESC", "LIMIT", "OFFSET", "IS", "NULL",
            "IN", "LIKE", "BETWEEN", "ESCAPE", "TRUE", "FALSE");
    private static final List<String> PRIVILEGES = List.of("SELECT", "INSERT", "UPDATE", "DELETE", "ALL");

    private Completion() {
    }

    record ObjectName(String schema, String name) {
    }

    record Item(String insert, String label, String detail, boolean openAgain) {
    }

    record Catalog(
            String schema,
            List<ObjectName> tables,
            List<ObjectName> views,
            List<ObjectName> procedures,
            List<ObjectName> generators,
            List<ObjectName> indexes,
            List<ObjectName> systemTables,
            List<String> triggers,
            List<String> schemas,
            List<String> roles,
            List<String> users,
            Map<String, List<String>> columns
    ) {
        static Catalog capture(Outline outline, String schema, Map<String, List<String>> columns) {
            if (outline == null) {
                return empty(schema, columns);
            }
            return new Catalog(
                    schema == null || schema.isBlank() ? "public" : schema,
                    names(outline.tables()),
                    names(outline.views()),
                    names(outline.procedures()),
                    outline.generators().stream().map(item -> new ObjectName(item.schema(), item.name())).toList(),
                    outline.indexes().stream().map(item -> new ObjectName(item.schema(), item.name())).toList(),
                    names(outline.systemTables()),
                    outline.triggers().stream().map(Outline.TriggerRef::name).toList(),
                    outline.schemas(),
                    outline.roles(),
                    outline.users(),
                    columns == null ? Map.of() : Map.copyOf(columns));
        }

        static Catalog empty(String schema, Map<String, List<String>> columns) {
            return new Catalog(
                    schema == null || schema.isBlank() ? "public" : schema,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(),
                    columns == null ? Map.of() : Map.copyOf(columns));
        }

        private static List<ObjectName> names(List<Outline.RelationName> relations) {
            List<ObjectName> names = new ArrayList<>();
            for (Outline.RelationName relation : relations) {
                names.add(new ObjectName(relation.schema(), relation.name()));
            }
            return List.copyOf(names);
        }
    }

    record Result(List<Item> items, List<ObjectName> missing, int replaceStart) {
        static Result empty(int caret) {
            return new Result(List.of(), List.of(), caret);
        }
    }

    static String key(String schema, String table) {
        String owner = schema == null || schema.isBlank() ? "public" : schema;
        return owner.toLowerCase(Locale.ROOT) + "\n" + table.toLowerCase(Locale.ROOT);
    }

    static Result suggest(String sql, int caret, Catalog catalog) {
        String text = sql == null ? "" : sql;
        int at = Math.max(0, Math.min(caret, text.length()));
        Catalog known = catalog == null ? Catalog.empty("public", Map.of()) : catalog;
        Scan scan = scan(text);
        if (scan.blocks(at)) {
            return Result.empty(at);
        }
        Prefix prefix = prefix(scan.tokens, text, at);
        List<Tok> statement = statement(scan.tokens, at);
        List<Tok> before = new ArrayList<>();
        for (Tok token : statement) {
            if (token == prefix.token) {
                continue;
            }
            if (token.end <= prefix.start) {
                before.add(token);
            }
        }
        List<Source> sources = sources(statement, known.schema);
        Bag bag = new Bag(prefix.text, known.schema);
        List<ObjectName> missing = new ArrayList<>();
        fill(bag, missing, statement, before, prefix, sources, known);
        return new Result(bag.finish(), List.copyOf(missing), prefix.start);
    }

    private static void fill(Bag bag, List<ObjectName> missing, List<Tok> statement, List<Tok> before, Prefix prefix,
                             List<Source> sources, Catalog catalog) {
        Chain chain = chain(before);
        if (chain != null) {
            fillChain(bag, missing, chain, sources, catalog, keywordBefore(before, before.size()));
            return;
        }
        Tok last = before.isEmpty() ? null : before.get(before.size() - 1);
        if (last == null || last.kind == Kind.SEMI) {
            keywords(bag, STATEMENTS, "keyword");
            return;
        }
        if (last.kind == Kind.WORD && last.keyword()) {
            switch (last.text.toUpperCase(Locale.ROOT)) {
                case "CREATE" -> keywords(bag, CREATE_KINDS, "keyword");
                case "DROP" -> keywords(bag, DROP_KINDS, "keyword");
                case "ALTER" -> keywords(bag, ALTER_KINDS, "keyword");
                case "SELECT", "RETURNING", "VALUES" -> expression(bag, missing, sources, catalog, true);
                case "WHERE", "AND", "OR", "WHEN", "THEN", "ELSE", "HAVING" ->
                        expression(bag, missing, sources, catalog, false);
                case "BY" -> columns(bag, missing, sources, catalog, null);
                case "FROM", "JOIN", "UPDATE", "INTO", "REFERENCES", "VIEW" -> relations(bag, catalog, null);
                case "TABLE" -> {
                    if (starts(statement, "CREATE")) {
                        keywords(bag, List.of("IF", "NOT", "EXISTS"), "keyword");
                        schemas(bag, catalog);
                    } else {
                        relations(bag, catalog, null);
                    }
                }
                case "SET" -> {
                    if (starts(statement, "UPDATE")) {
                        columns(bag, missing, sources, catalog, null);
                    } else {
                        keywords(bag, List.of("SCHEMA", "GENERATOR"), "keyword");
                    }
                }
                case "INSERT" -> keywords(bag, List.of("INTO"), "keyword");
                case "DELETE" -> keywords(bag, List.of("FROM"), "keyword");
                case "ORDER" -> keywords(bag, List.of("BY"), "keyword");
                case "EXECUTE" -> keywords(bag, List.of("PROCEDURE"), "keyword");
                case "PROCEDURE" -> {
                    if (!created(before)) {
                        objects(bag, catalog.procedures, null, "procedure", 0);
                    }
                }
                case "GENERATOR", "SEQUENCE" -> {
                    if (!created(before)) {
                        objects(bag, catalog.generators, null, "generator", 0);
                    }
                }
                case "INDEX" -> {
                    if (dropped(before)) {
                        objects(bag, catalog.indexes, null, "index", 0);
                    }
                }
                case "TRIGGER" -> {
                    if (!created(before)) {
                        names(bag, catalog.triggers, "trigger");
                    }
                }
                case "ROLE" -> {
                    if (!created(before)) {
                        names(bag, catalog.roles, "role");
                    }
                }
                case "SCHEMA" -> names(bag, catalog.schemas, "schema");
                case "FOR", "ON" -> {
                    if (!(last.text.equalsIgnoreCase("FOR") && nextIs(statement, last, "SELECT"))) {
                        relations(bag, catalog, null);
                    }
                }
                case "TO" -> {
                    if (!has(statement, "GENERATOR")) {
                        principals(bag, catalog);
                    }
                }
                case "GRANT", "REVOKE" -> {
                    keywords(bag, PRIVILEGES, "privilege");
                    names(bag, catalog.roles, "role");
                }
                case "ADD" -> keywords(bag, List.of("COLUMN", "CONSTRAINT", "PRIMARY", "FOREIGN", "UNIQUE"), "keyword");
                case "PRIMARY", "FOREIGN" -> keywords(bag, List.of("KEY"), "keyword");
                case "UNIQUE" -> keywords(bag, List.of("INDEX"), "keyword");
                case "IF", "EXISTS" -> {
                    int cut = before.size() - 1;
                    while (cut >= 0 && (before.get(cut).isKeyword("IF") || before.get(cut).isKeyword("NOT")
                            || before.get(cut).isKeyword("EXISTS"))) {
                        cut--;
                    }
                    if (cut >= 0) {
                        fill(bag, missing, statement, before.subList(0, cut + 1), prefix, sources, catalog);
                    }
                }
                case "NOT" -> {
                    Tok earlier = wordBefore(before, before.size() - 1);
                    if (earlier != null && earlier.isKeyword("IF")) {
                        keywords(bag, List.of("EXISTS"), "keyword");
                    } else if (insideCreateTable(statement, prefix.start)) {
                        keywords(bag, List.of("NULL"), "keyword");
                    } else {
                        expression(bag, missing, sources, catalog, false);
                    }
                }
                case "AS" -> {
                    if (starts(statement, "CREATE") && has(statement, "VIEW")) {
                        keywords(bag, List.of("SELECT"), "keyword");
                    }
                }
                case "DISTINCT" -> expression(bag, missing, sources, catalog, true);
                case "IS", "IN", "LIKE", "BETWEEN" -> expression(bag, missing, sources, catalog, false);
                case "NULL" -> keywords(bag, List.of("AND", "OR", "ORDER"), "keyword");
                default -> {
                    if (typeWord(last.text)) {
                        keywords(bag, List.of("NOT", "NULL", "PRIMARY", "KEY", "DEFAULT", "UNIQUE", "REFERENCES"), "keyword");
                    }
                }
            }
            return;
        }
        if (last.kind == Kind.LPAREN || last.kind == Kind.COMMA) {
            if (insideCreateTable(statement, prefix.start)) {
                keywords(bag, List.of("CONSTRAINT", "PRIMARY", "FOREIGN", "UNIQUE"), "keyword");
            } else if (insertColumns(statement, prefix.start)) {
                columns(bag, missing, sources, catalog, null);
            } else {
                expression(bag, missing, sources, catalog, false);
            }
            return;
        }
        if (last.kind == Kind.WORD) {
            Tok previous = wordBefore(before, before.size() - 1);
            if (previous != null && (previous.isKeyword("ADD") || previous.isKeyword("COLUMN")
                    || ((previous.kind == Kind.LPAREN || previous.kind == Kind.COMMA)
                    && insideCreateTable(statement, prefix.start)))) {
                types(bag);
                return;
            }
            if (previous != null && previous.isKeyword("BY")) {
                keywords(bag, List.of("ASC", "DESC"), "keyword");
                return;
            }
            if (previous != null && previous.keyword() && relationIntro(previous.text)) {
                keywords(bag, List.of("AS", "WHERE", "ORDER", "LIMIT"), "keyword");
                if (starts(statement, "UPDATE")) {
                    keywords(bag, List.of("SET"), "keyword");
                }
                if (starts(statement, "INSERT")) {
                    keywords(bag, List.of("VALUES", "SELECT"), "keyword");
                }
                return;
            }
        }
        if (last.kind == Kind.OTHER || last.kind == Kind.RPAREN) {
            expression(bag, missing, sources, catalog, false);
        }
    }

    private static void fillChain(Bag bag, List<ObjectName> missing, Chain chain, List<Source> sources,
                                  Catalog catalog, String intro) {
        if (chain.parts.size() >= 2) {
            String schema = chain.parts.get(chain.parts.size() - 2);
            String table = chain.parts.get(chain.parts.size() - 1);
            columns(bag, missing, List.of(new Source(schema, table, null)), catalog, null);
            return;
        }
        String name = chain.parts.get(0);
        Source source = resolve(name, sources, catalog);
        boolean schema = schema(catalog, name);
        boolean relation = intro != null && relationIntro(intro);
        if (source != null && !(schema && relation && !alias(sources, name))) {
            columns(bag, missing, List.of(source), catalog, null);
            return;
        }
        if (schema) {
            relations(bag, catalog, name);
            return;
        }
        if (source != null) {
            columns(bag, missing, List.of(source), catalog, null);
        }
    }

    private static void expression(Bag bag, List<ObjectName> missing, List<Source> sources, Catalog catalog, boolean select) {
        columns(bag, missing, sources, catalog, null);
        for (String function : FUNCTIONS) {
            bag.add(1, new Item(function + "(", function + "(", "function", false));
        }
        for (String name : CONTEXTS) {
            bag.add(2, new Item(name, name, "value", false));
        }
        keywords(bag, select ? SELECT_WORDS : FILTER_WORDS, "keyword");
    }

    private static void columns(Bag bag, List<ObjectName> missing, List<Source> sources, Catalog catalog, String only) {
        List<Source> used = new ArrayList<>();
        for (Source source : sources) {
            if (only != null && !source.matches(only)) {
                continue;
            }
            used.add(source);
            if (!catalog.columns.containsKey(key(source.schema, source.table))) {
                missing.add(new ObjectName(source.schema, source.table));
            }
        }
        boolean qualify = used.size() > 1;
        for (Source source : used) {
            List<String> names = catalog.columns.get(key(source.schema, source.table));
            if (names == null) {
                continue;
            }
            String owner = source.alias != null ? source.alias : source.table;
            for (String name : names) {
                if (qualify) {
                    String insert = ident(owner) + "." + ident(name);
                    bag.add(0, new Item(insert, owner + "." + name, "column", false));
                } else {
                    bag.add(0, new Item(ident(name), name, "column", false));
                }
            }
        }
    }

    private static void relations(Bag bag, Catalog catalog, String schema) {
        objects(bag, catalog.tables, schema, "table", 0);
        objects(bag, catalog.views, schema, "view", 1);
        objects(bag, catalog.procedures, schema, "procedure", 2);
        if (schema == null) {
            objects(bag, catalog.systemTables, null, "system", 4);
            schemas(bag, catalog);
        }
    }

    private static void objects(Bag bag, List<ObjectName> objects, String schema, String detail, int rank) {
        for (ObjectName object : objects) {
            if (schema != null) {
                if (!same(object.schema, schema)) {
                    continue;
                }
                bag.add(rank, new Item(ident(object.name), object.name, detail, false));
                continue;
            }
            if (same(object.schema, bag.catalogSchema)) {
                bag.add(rank, new Item(ident(object.name), object.name, detail, false));
            } else {
                String label = object.schema + "." + object.name;
                bag.add(rank + 3, new Item(ident(object.schema) + "." + ident(object.name), label, detail, false), object.name);
            }
        }
    }

    private static void schemas(Bag bag, Catalog catalog) {
        for (String schema : catalog.schemas) {
            bag.add(5, new Item(ident(schema) + ".", schema, "schema", true));
        }
    }

    private static void names(Bag bag, List<String> values, String detail) {
        for (String value : values) {
            bag.add(0, new Item(ident(value), value, detail, false));
        }
    }

    private static void principals(Bag bag, Catalog catalog) {
        names(bag, catalog.users, "user");
        names(bag, catalog.roles, "role");
        keywords(bag, List.of("PUBLIC"), "keyword");
    }

    private static void types(Bag bag) {
        for (String type : TYPES) {
            bag.add(0, new Item(type, type, "type", false));
        }
    }

    private static void keywords(Bag bag, List<String> words, String detail) {
        for (String word : words) {
            bag.add(3, new Item(word, word, detail, false));
        }
    }

    private static Source resolve(String name, List<Source> sources, Catalog catalog) {
        for (Source source : sources) {
            if (same(source.alias, name) || (source.alias == null && same(source.table, name))) {
                return source;
            }
        }
        ObjectName found = find(catalog.tables, name, catalog.schema);
        if (found == null) {
            found = find(catalog.views, name, catalog.schema);
        }
        if (found == null) {
            found = find(catalog.systemTables, name, catalog.schema);
        }
        if (found == null) {
            return null;
        }
        return new Source(found.schema, found.name, null);
    }

    private static ObjectName find(List<ObjectName> objects, String name, String preferred) {
        ObjectName other = null;
        for (ObjectName object : objects) {
            if (!same(object.name, name)) {
                continue;
            }
            if (same(object.schema, preferred)) {
                return object;
            }
            if (other == null) {
                other = object;
            }
        }
        return other;
    }

    private static boolean schema(Catalog catalog, String name) {
        for (String schema : catalog.schemas) {
            if (same(schema, name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean alias(List<Source> sources, String name) {
        for (Source source : sources) {
            if (same(source.alias, name)) {
                return true;
            }
        }
        return false;
    }

    private static List<Source> sources(List<Tok> statement, String current) {
        List<Source> found = new ArrayList<>();
        for (int i = 0; i < statement.size(); i++) {
            Tok token = statement.get(i);
            if (!token.keyword() || !relationIntro(token.text)) {
                continue;
            }
            if (token.isKeyword("FOR") && i + 1 < statement.size() && statement.get(i + 1).isKeyword("SELECT")) {
                continue;
            }
            int at = skipExists(statement, i + 1);
            Qualified name = readName(statement, at);
            if (name == null) {
                continue;
            }
            if (token.isKeyword("FROM") && name.next < statement.size()
                    && statement.get(name.next).kind == Kind.LPAREN) {
                continue;
            }
            String schema = name.schema == null ? current : name.schema;
            String alias = null;
            int next = name.next;
            if (next < statement.size() && statement.get(next).isKeyword("AS")
                    && next + 1 < statement.size() && statement.get(next + 1).kind == Kind.WORD
                    && !statement.get(next + 1).keyword()) {
                alias = statement.get(next + 1).text;
            } else if (next < statement.size() && statement.get(next).kind == Kind.WORD && !statement.get(next).keyword()) {
                alias = statement.get(next).text;
            }
            found.add(new Source(schema, name.name, alias));
        }
        return found;
    }

    private static int skipExists(List<Tok> tokens, int index) {
        if (index < tokens.size() && tokens.get(index).isKeyword("IF")) {
            if (index + 1 < tokens.size() && tokens.get(index + 1).isKeyword("EXISTS")) {
                return index + 2;
            }
            if (index + 2 < tokens.size() && tokens.get(index + 1).isKeyword("NOT")
                    && tokens.get(index + 2).isKeyword("EXISTS")) {
                return index + 3;
            }
        }
        return index;
    }

    private static Qualified readName(List<Tok> tokens, int index) {
        if (index >= tokens.size() || tokens.get(index).kind != Kind.WORD || tokens.get(index).keyword()) {
            return null;
        }
        Tok first = tokens.get(index);
        if (index + 2 < tokens.size() && tokens.get(index + 1).kind == Kind.DOT && tokens.get(index + 2).kind == Kind.WORD
                && !tokens.get(index + 2).keyword()) {
            return new Qualified(first.text, tokens.get(index + 2).text, index + 3);
        }
        return new Qualified(null, first.text, index + 1);
    }

    private static boolean relationIntro(String word) {
        return switch (word.toUpperCase(Locale.ROOT)) {
            case "FROM", "JOIN", "UPDATE", "INTO", "TABLE", "VIEW", "REFERENCES", "FOR", "ON" -> true;
            default -> false;
        };
    }

    private static boolean insideCreateTable(List<Tok> statement, int position) {
        int depth = 0;
        int opened = -1;
        boolean create = false;
        boolean table = false;
        for (Tok token : statement) {
            if (token.start >= position) {
                break;
            }
            if (token.isKeyword("CREATE")) {
                create = true;
                table = false;
            } else if (create && token.isKeyword("TABLE")) {
                table = true;
            } else if (token.kind == Kind.LPAREN) {
                if (table && opened < 0) {
                    opened = depth;
                }
                depth++;
            } else if (token.kind == Kind.RPAREN) {
                depth--;
                if (opened >= 0 && depth <= opened) {
                    opened = -1;
                    table = false;
                }
            }
        }
        return opened >= 0 && depth > opened;
    }

    private static boolean insertColumns(List<Tok> statement, int position) {
        if (!starts(statement, "INSERT")) {
            return false;
        }
        boolean into = false;
        boolean values = false;
        int depth = 0;
        int opened = -1;
        for (Tok token : statement) {
            if (token.start >= position) {
                break;
            }
            if (token.isKeyword("INTO")) {
                into = true;
            } else if (token.isKeyword("VALUES")) {
                values = true;
            } else if (token.kind == Kind.LPAREN) {
                if (into && !values && opened < 0) {
                    opened = depth;
                }
                depth++;
            } else if (token.kind == Kind.RPAREN) {
                depth--;
                if (opened >= 0 && depth <= opened) {
                    opened = -1;
                }
            }
        }
        return opened >= 0 && depth > opened;
    }

    private static boolean typeWord(String word) {
        for (String type : TYPE_WORDS) {
            if (type.equalsIgnoreCase(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean created(List<Tok> before) {
        Tok word = wordBefore(before, before.size() - 1);
        return word != null && word.isKeyword("CREATE");
    }

    private static boolean dropped(List<Tok> before) {
        Tok word = wordBefore(before, before.size() - 1);
        return word != null && word.isKeyword("DROP");
    }

    private static Tok wordBefore(List<Tok> tokens, int index) {
        for (int i = index - 1; i >= 0; i--) {
            Tok token = tokens.get(i);
            if (token.kind == Kind.WORD || token.kind == Kind.LPAREN || token.kind == Kind.COMMA) {
                return token;
            }
            if (token.kind == Kind.DOT) {
                continue;
            }
            if (token.kind == Kind.WORD) {
                return token;
            }
        }
        return null;
    }

    private static String keywordBefore(List<Tok> tokens, int index) {
        for (int i = index - 1; i >= 0; i--) {
            if (tokens.get(i).keyword()) {
                return tokens.get(i).text;
            }
        }
        return null;
    }

    private static boolean starts(List<Tok> statement, String keyword) {
        for (Tok token : statement) {
            if (token.kind == Kind.WORD) {
                return token.isKeyword(keyword);
            }
        }
        return false;
    }

    private static boolean has(List<Tok> statement, String keyword) {
        for (Tok token : statement) {
            if (token.isKeyword(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean nextIs(List<Tok> statement, Tok token, String keyword) {
        for (int i = 0; i < statement.size(); i++) {
            if (statement.get(i) == token) {
                return i + 1 < statement.size() && statement.get(i + 1).isKeyword(keyword);
            }
        }
        return false;
    }

    private static Chain chain(List<Tok> before) {
        if (before.isEmpty() || before.get(before.size() - 1).kind != Kind.DOT) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        int index = before.size() - 2;
        while (index >= 0 && before.get(index).kind == Kind.WORD) {
            parts.add(before.get(index).text);
            if (index - 1 >= 0 && before.get(index - 1).kind == Kind.DOT) {
                index -= 2;
                continue;
            }
            break;
        }
        if (parts.isEmpty()) {
            return null;
        }
        java.util.Collections.reverse(parts);
        return new Chain(parts);
    }

    private static List<Tok> statement(List<Tok> tokens, int caret) {
        int from = 0;
        int to = Integer.MAX_VALUE;
        for (Tok token : tokens) {
            if (token.kind == Kind.SEMI && token.end <= caret) {
                from = token.end;
            }
            if (token.kind == Kind.SEMI && token.start >= caret && to == Integer.MAX_VALUE) {
                to = token.start;
            }
        }
        List<Tok> statement = new ArrayList<>();
        for (Tok token : tokens) {
            if (token.start >= from && token.start < to) {
                statement.add(token);
            }
        }
        return statement;
    }

    private static Prefix prefix(List<Tok> tokens, String text, int caret) {
        for (Tok token : tokens) {
            if (token.kind == Kind.WORD && token.start < caret && token.end >= caret) {
                String value = token.quoted
                        ? text.substring(Math.min(text.length(), token.start + 1), caret).replace("\"\"", "\"")
                        : text.substring(token.start, caret);
                return new Prefix(token, token.start, value);
            }
        }
        return new Prefix(null, caret, "");
    }

    private static boolean same(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    static String ident(String name) {
        if (name != null && name.matches("[A-Za-z_][A-Za-z0-9_$]*") && !Highlighter.keyword(name)) {
            return name;
        }
        return SqlNames.quote(name == null ? "" : name);
    }

    private static Scan scan(String sql) {
        List<Tok> tokens = new ArrayList<>();
        List<Range> blocked = new ArrayList<>();
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                int start = index;
                index += 2;
                while (index < sql.length() && sql.charAt(index) != '\n') {
                    index++;
                }
                blocked.add(new Range(start, index < sql.length() ? index : sql.length() + 1));
                continue;
            }
            if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                int start = index;
                index += 2;
                boolean closed = false;
                while (index + 1 < sql.length()) {
                    if (sql.charAt(index) == '*' && sql.charAt(index + 1) == '/') {
                        index += 2;
                        closed = true;
                        break;
                    }
                    index++;
                }
                if (!closed) {
                    index = sql.length();
                }
                blocked.add(new Range(start, closed ? index : sql.length() + 1));
                continue;
            }
            if (current == '\'') {
                int start = index;
                index++;
                boolean closed = false;
                while (index < sql.length()) {
                    if (sql.charAt(index) == '\'') {
                        if (index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                            index += 2;
                            continue;
                        }
                        index++;
                        closed = true;
                        break;
                    }
                    if (sql.charAt(index) == '\n') {
                        break;
                    }
                    index++;
                }
                boolean atNewline = index < sql.length() && sql.charAt(index) == '\n';
                blocked.add(new Range(start, closed || atNewline ? index : sql.length() + 1));
                continue;
            }
            if (current == '"') {
                int start = index;
                index++;
                StringBuilder inner = new StringBuilder();
                while (index < sql.length()) {
                    char next = sql.charAt(index);
                    if (next == '"') {
                        if (index + 1 < sql.length() && sql.charAt(index + 1) == '"') {
                            inner.append('"');
                            index += 2;
                            continue;
                        }
                        index++;
                        break;
                    }
                    inner.append(next);
                    index++;
                }
                tokens.add(new Tok(Kind.WORD, inner.toString(), start, index, true));
                continue;
            }
            if (Character.isLetter(current) || current == '_' || current == '$') {
                int start = index;
                index++;
                while (index < sql.length()) {
                    char next = sql.charAt(index);
                    if (!Character.isLetterOrDigit(next) && next != '_' && next != '$') {
                        break;
                    }
                    index++;
                }
                tokens.add(new Tok(Kind.WORD, sql.substring(start, index), start, index, false));
                continue;
            }
            if (current == '.' && !tokens.isEmpty() && tokens.get(tokens.size() - 1).kind == Kind.WORD) {
                tokens.add(new Tok(Kind.DOT, ".", index, index + 1, false));
                index++;
                continue;
            }
            Kind kind = switch (current) {
                case ',' -> Kind.COMMA;
                case ';' -> Kind.SEMI;
                case '(' -> Kind.LPAREN;
                case ')' -> Kind.RPAREN;
                default -> Kind.OTHER;
            };
            tokens.add(new Tok(kind, String.valueOf(current), index, index + 1, false));
            index++;
        }
        return new Scan(tokens, blocked);
    }

    private enum Kind {
        WORD, DOT, COMMA, SEMI, LPAREN, RPAREN, OTHER
    }

    private record Tok(Kind kind, String text, int start, int end, boolean quoted) {
        boolean keyword() {
            return kind == Kind.WORD && !quoted && Highlighter.keyword(text);
        }

        boolean isKeyword(String word) {
            return keyword() && text.equalsIgnoreCase(word);
        }
    }

    private record Range(int start, int end) {
    }

    private record Scan(List<Tok> tokens, List<Range> blocked) {
        boolean blocks(int caret) {
            for (Range range : blocked) {
                if (caret >= range.start && caret < range.end) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Prefix(Tok token, int start, String text) {
    }

    private record Chain(List<String> parts) {
    }

    private record Qualified(String schema, String name, int next) {
    }

    private record Source(String schema, String table, String alias) {
        boolean matches(String name) {
            return same(alias, name) || same(table, name);
        }
    }

    private static final class Bag {
        private final String prefix;
        private final String catalogSchema;
        private final List<Row> rows = new ArrayList<>();

        private Bag(String prefix, String schema) {
            this.prefix = prefix == null ? "" : prefix;
            this.catalogSchema = schema == null || schema.isBlank() ? "public" : schema;
        }

        private void add(int rank, Item item) {
            add(rank, item, item.label);
        }

        private void add(int rank, Item item, String matchName) {
            if (!matches(item.label, matchName)) {
                return;
            }
            for (Row row : rows) {
                if (row.item.insert.equals(item.insert) && row.item.detail.equals(item.detail)) {
                    return;
                }
            }
            rows.add(new Row(rank, item));
        }

        private boolean matches(String label, String name) {
            return prefix.isEmpty()
                    || label.regionMatches(true, 0, prefix, 0, prefix.length())
                    || name.regionMatches(true, 0, prefix, 0, prefix.length());
        }

        private List<Item> finish() {
            rows.sort(Comparator.comparingInt((Row row) -> row.rank)
                    .thenComparing(row -> row.item.label, String.CASE_INSENSITIVE_ORDER));
            List<Item> items = new ArrayList<>();
            for (Row row : rows) {
                if (items.size() == LIMIT) {
                    break;
                }
                items.add(row.item);
            }
            return List.copyOf(items);
        }
    }

    private record Row(int rank, Item item) {
    }
}
