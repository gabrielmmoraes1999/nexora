package io.github.gabrielmmoraes1999.nexora;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class Evaluator {

    @FunctionalInterface
    interface Sequences {
        long next(String name, Long step);
    }

    @FunctionalInterface
    interface Contexts {
        Object read(String name, Integer precision);
    }

    private final TableStore table;
    private final Object[] params;
    private final String alias;
    private final RoutineScope scope;
    private final Sequences sequences;
    private final Contexts contexts;
    private StoredRow current;
    private Schema viewSchema;
    private String viewQualifier;
    private Object[] viewRow;

    Evaluator(TableStore table, Object[] params, String alias) {
        this(table, params, alias, null, null, null);
    }

    Evaluator(TableStore table, Object[] params, String alias, RoutineScope scope, Sequences sequences, Contexts contexts) {
        this.table = table;
        this.params = params;
        this.alias = alias;
        this.scope = scope;
        this.sequences = sequences;
        this.contexts = contexts;
    }

    void bind(StoredRow row) {
        this.current = row;
    }

    void useView(Schema schema, String qualifier) {
        this.viewSchema = schema;
        this.viewQualifier = qualifier;
    }

    void bindView(Object[] row) {
        this.viewRow = row;
        this.current = null;
    }

    boolean owns(ColumnRef column) {
        if (table == null) {
            return false;
        }
        if (column.qualifier() == null) {
            return true;
        }
        return Names.same(column.qualifier(), table.schema().name) || Names.same(column.qualifier(), alias);
    }

    boolean truth(Expr expr) {
        Object value = eval(expr);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new NexException("condição precisa ser lógica");
    }

    Object eval(Expr expr) {
        return switch (expr) {
            case Literal literal -> literal.value();
            case Param param -> param(param);
            case ColumnRef column -> column(column);
            case VarRef variable -> {
                if (scope == null) {
                    throw new NexException("variável " + variable.name() + " não pode ser usada neste comando");
                }
                yield scope.variable(variable.name());
            }
            case NextValue next -> nextValue(next);
            case ContextRef context -> {
                if (contexts == null) {
                    throw new NexException("contexto " + context.name() + " não pode ser usado neste comando");
                }
                yield contexts.read(context.name(), context.precision());
            }
            case Unary unary -> unary(unary);
            case Binary binary -> binary(binary);
            case IsNull isNull -> {
                boolean absent = eval(isNull.expr()) == null;
                yield isNull.negated() ? !absent : absent;
            }
            case Like like -> like(like);
            case InList inList -> inList(inList);
            case Between between -> between(between);
            case Call call -> call(call);
        };
    }

    Object evalAggregate(Expr expr, List<StoredRow> rows) {
        Expr folded = foldAggregates(expr, rows);
        current = null;
        if (containsColumn(folded)) {
            throw new NexException("não é possível misturar agregação e colunas sem GROUP BY");
        }
        return eval(folded);
    }

    Object evalAggregateValues(Expr expr, List<Object[]> rows) {
        Expr folded = foldAggregatesValues(expr, rows);
        viewRow = null;
        if (containsColumn(folded)) {
            throw new NexException("não é possível misturar agregação e colunas sem GROUP BY");
        }
        return eval(folded);
    }

    static boolean containsColumn(Expr expr) {
        return switch (expr) {
            case ColumnRef ignored -> true;
            case Call call -> call.args().stream().anyMatch(Evaluator::containsColumn);
            case Unary unary -> containsColumn(unary.expr());
            case Binary binary -> containsColumn(binary.left()) || containsColumn(binary.right());
            case IsNull isNull -> containsColumn(isNull.expr());
            case Like like -> containsColumn(like.expr()) || containsColumn(like.pattern())
                    || (like.escape() != null && containsColumn(like.escape()));
            case InList inList -> containsColumn(inList.expr()) || inList.values().stream().anyMatch(Evaluator::containsColumn);
            case Between between -> containsColumn(between.expr()) || containsColumn(between.low()) || containsColumn(between.high());
            default -> false;
        };
    }

    private Expr foldAggregates(Expr expr, List<StoredRow> rows) {
        return switch (expr) {
            case Call call when aggregate(call.name()) -> new Literal(computeAggregate(call, rows));
            case Call call -> new Call(call.name(), call.distinct(), call.star(), foldAll(call.args(), rows));
            case Unary unary -> new Unary(unary.op(), foldAggregates(unary.expr(), rows));
            case Binary binary -> new Binary(binary.op(), foldAggregates(binary.left(), rows), foldAggregates(binary.right(), rows));
            case IsNull isNull -> new IsNull(foldAggregates(isNull.expr(), rows), isNull.negated());
            case Like like -> new Like(foldAggregates(like.expr(), rows), foldAggregates(like.pattern(), rows),
                    like.escape() == null ? null : foldAggregates(like.escape(), rows), like.negated());
            case InList inList -> new InList(foldAggregates(inList.expr(), rows), foldAll(inList.values(), rows), inList.negated());
            case Between between -> new Between(foldAggregates(between.expr(), rows), foldAggregates(between.low(), rows),
                    foldAggregates(between.high(), rows), between.negated());
            default -> expr;
        };
    }

    private List<Expr> foldAll(List<Expr> expressions, List<StoredRow> rows) {
        List<Expr> folded = new ArrayList<>(expressions.size());
        for (Expr expr : expressions) {
            folded.add(foldAggregates(expr, rows));
        }
        return folded;
    }

    private Expr foldAggregatesValues(Expr expr, List<Object[]> rows) {
        return switch (expr) {
            case Call call when aggregate(call.name()) -> new Literal(computeAggregateValues(call, rows));
            case Call call -> new Call(call.name(), call.distinct(), call.star(), foldAllValues(call.args(), rows));
            case Unary unary -> new Unary(unary.op(), foldAggregatesValues(unary.expr(), rows));
            case Binary binary -> new Binary(binary.op(), foldAggregatesValues(binary.left(), rows), foldAggregatesValues(binary.right(), rows));
            case IsNull isNull -> new IsNull(foldAggregatesValues(isNull.expr(), rows), isNull.negated());
            case Like like -> new Like(foldAggregatesValues(like.expr(), rows), foldAggregatesValues(like.pattern(), rows),
                    like.escape() == null ? null : foldAggregatesValues(like.escape(), rows), like.negated());
            case InList inList -> new InList(foldAggregatesValues(inList.expr(), rows), foldAllValues(inList.values(), rows), inList.negated());
            case Between between -> new Between(foldAggregatesValues(between.expr(), rows), foldAggregatesValues(between.low(), rows),
                    foldAggregatesValues(between.high(), rows), between.negated());
            default -> expr;
        };
    }

    private List<Expr> foldAllValues(List<Expr> expressions, List<Object[]> rows) {
        List<Expr> folded = new ArrayList<>(expressions.size());
        for (Expr expr : expressions) {
            folded.add(foldAggregatesValues(expr, rows));
        }
        return folded;
    }

    static boolean containsAggregate(Expr expr) {
        return switch (expr) {
            case Call call when aggregate(call.name()) -> true;
            case Call call -> call.args().stream().anyMatch(Evaluator::containsAggregate);
            case Unary unary -> containsAggregate(unary.expr());
            case Binary binary -> containsAggregate(binary.left()) || containsAggregate(binary.right());
            case IsNull isNull -> containsAggregate(isNull.expr());
            case Like like -> containsAggregate(like.expr()) || containsAggregate(like.pattern())
                    || (like.escape() != null && containsAggregate(like.escape()));
            case InList inList -> containsAggregate(inList.expr()) || inList.values().stream().anyMatch(Evaluator::containsAggregate);
            case Between between -> containsAggregate(between.expr()) || containsAggregate(between.low()) || containsAggregate(between.high());
            default -> false;
        };
    }

    static boolean aggregate(String name) {
        String folded = name.toUpperCase(Locale.ROOT);
        return folded.equals("COUNT") || folded.equals("SUM") || folded.equals("MIN")
                || folded.equals("MAX") || folded.equals("AVG");
    }

    private Object computeAggregate(Call call, List<StoredRow> rows) {
        return computeAggregate(call, rows.size(), index -> bind(rows.get(index)));
    }

    private Object computeAggregateValues(Call call, List<Object[]> rows) {
        return computeAggregate(call, rows.size(), index -> bindView(rows.get(index)));
    }

    private Object computeAggregate(Call call, int size, java.util.function.IntConsumer bindRow) {
        String name = call.name().toUpperCase(Locale.ROOT);
        if (call.star()) {
            if (!name.equals("COUNT")) {
                throw new NexException(name + " não aceita *");
            }
            return (long) size;
        }
        if (call.args().size() != 1) {
            throw new NexException(name + " espera um argumento");
        }
        List<Object> values = new ArrayList<>();
        Set<IndexKey> seen = new HashSet<>();
        Expr arg = call.args().get(0);
        for (int index = 0; index < size; index++) {
            bindRow.accept(index);
            Object value = eval(arg);
            if (value == null) {
                continue;
            }
            if (call.distinct() && !seen.add(new IndexKey(new Object[]{value}))) {
                continue;
            }
            values.add(value);
        }
        return switch (name) {
            case "COUNT" -> (long) values.size();
            case "SUM" -> sum(values);
            case "AVG" -> average(values);
            case "MIN" -> extreme(values, true);
            case "MAX" -> extreme(values, false);
            default -> throw new NexException("função desconhecida: " + call.name());
        };
    }

    private Object sum(List<Object> values) {
        if (values.isEmpty()) {
            return null;
        }
        boolean floating = false;
        boolean decimal = false;
        for (Object value : values) {
            if (value instanceof Double || value instanceof Float) {
                floating = true;
            } else if (value instanceof BigDecimal) {
                decimal = true;
            } else if (!(value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte)) {
                throw new NexException("SUM espera valores numéricos");
            }
        }
        if (floating) {
            double total = 0;
            for (Object value : values) {
                total += Values.toDouble(value);
            }
            return total;
        }
        if (decimal) {
            BigDecimal total = BigDecimal.ZERO;
            for (Object value : values) {
                total = total.add(Values.asDecimal(value));
            }
            return total;
        }
        long total = 0;
        for (Object value : values) {
            total = Math.addExact(total, Values.toLong(value));
        }
        return total;
    }

    private Object average(List<Object> values) {
        Object total = sum(values);
        if (total == null) {
            return null;
        }
        if (total instanceof BigDecimal decimal) {
            return decimal.divide(BigDecimal.valueOf(values.size()), 8, RoundingMode.HALF_UP).stripTrailingZeros();
        }
        return ((Number) total).doubleValue() / values.size();
    }

    private Object extreme(List<Object> values, boolean minimum) {
        Object best = null;
        for (Object value : values) {
            if (best == null || (minimum ? Values.compare(value, best) < 0 : Values.compare(value, best) > 0)) {
                best = value;
            }
        }
        return best;
    }

    private Object param(Param param) {
        if (param.index() < 0 || param.index() >= params.length) {
            throw new NexException("parâmetro ?" + (param.index() + 1) + " não foi informado");
        }
        return params[param.index()];
    }

    private Object column(ColumnRef column) {
        if (column.qualifier() != null && (Names.same(column.qualifier(), "NEW") || Names.same(column.qualifier(), "OLD"))) {
            if (scope == null) {
                throw new NexException(column.qualifier() + " não existe neste contexto");
            }
            return scope.field(column.qualifier(), column.name());
        }
        String qualifier = column.qualifier();
        if (qualifier != null && alias != null && Names.same(qualifier, alias)) {
            qualifier = null;
        }
        if (scope != null && qualifier == null && Names.same(column.name(), "ROW_COUNT") && !scope.hasVariable(column.name())
                && (scope.preferVariables || table == null || current == null)) {
            return scope.rowCount;
        }
        if (scope != null && qualifier == null && scope.preferVariables && scope.hasVariable(column.name())) {
            return scope.variable(column.name());
        }
        if (viewSchema != null) {
            boolean named = qualifier == null
                    || Names.same(qualifier, viewSchema.name)
                    || Names.same(qualifier, viewQualifier)
                    || (alias != null && Names.same(qualifier, alias));
            if (!named) {
                throw new NexException("qualificador desconhecido: " + column.qualifier());
            }
            if (viewRow == null) {
                throw new NexException("coluna " + column.name() + " não pode ser usada neste comando");
            }
            int index = viewSchema.indexOf(column.name());
            if (index < 0) {
                throw new NexException("coluna não encontrada: " + column.name());
            }
            Object value = viewRow[index];
            return value == Values.MISSING ? null : value;
        }
        if (table == null || current == null) {
            if (scope != null && qualifier == null && scope.hasVariable(column.name())) {
                return scope.variable(column.name());
            }
            throw new NexException("coluna " + column.name() + " não pode ser usada neste comando");
        }
        if (qualifier != null
                && !Names.same(qualifier, table.schema().name)
                && !Names.same(qualifier, table.namespace() + "." + table.schema().name)
                && !Names.same(qualifier, alias)) {
            throw new NexException("qualificador desconhecido: " + column.qualifier());
        }
        int index = table.schema().indexOf(column.name());
        if (index < 0) {
            throw new NexException("coluna não encontrada: " + column.name());
        }
        return table.value(current, index);
    }

    private long nextValue(NextValue next) {
        if (sequences == null) {
            throw new NexException("gerador não pode ser usado neste comando");
        }
        return sequences.next(next.name(), null);
    }

    private long genId(Call call) {
        if (call.args().size() != 2) {
            throw new NexException("GEN_ID precisa do nome do gerador e do passo");
        }
        if (sequences == null) {
            throw new NexException("gerador não pode ser usado neste comando");
        }
        Expr name = call.args().get(0);
        String generator;
        if (name instanceof ColumnRef column) {
            generator = column.qualifier() == null ? column.name() : column.qualifier() + "." + column.name();
        } else if (name instanceof Literal literal && literal.value() instanceof String text) {
            generator = text;
        } else {
            throw new NexException("GEN_ID precisa do nome do gerador");
        }
        Object step = eval(call.args().get(1));
        if (step == null) {
            throw new NexException("passo do GEN_ID não pode ser nulo");
        }
        return sequences.next(generator, Values.toLong(step));
    }

    private Object unary(Unary unary) {
        Object value = eval(unary.expr());
        if (value == null) {
            return null;
        }
        return switch (unary.op()) {
            case NOT -> {
                if (!(value instanceof Boolean bool)) {
                    throw new NexException("NOT exige uma expressão lógica");
                }
                yield !bool;
            }
            case PLUS -> number(value, "unário +");
            case NEGATE -> negate(value);
        };
    }

    private Object negate(Object value) {
        if (value instanceof Double || value instanceof Float) {
            return -Values.toDouble(value);
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.negate();
        }
        if (value instanceof Long) {
            return Math.negateExact(((Number) value).longValue());
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return Math.negateExact(((Number) value).intValue());
        }
        throw new NexException("não é possível negar " + Values.display(value));
    }

    private Object binary(Binary binary) {
        if (binary.op() == BinaryOp.AND) {
            return logicAnd(binary.left(), binary.right());
        }
        if (binary.op() == BinaryOp.OR) {
            return logicOr(binary.left(), binary.right());
        }
        Object left = eval(binary.left());
        Object right = eval(binary.right());
        if (binary.op() == BinaryOp.CONCAT) {
            if (left == null || right == null) {
                return null;
            }
            return text(left) + text(right);
        }
        if (left == null || right == null) {
            return null;
        }
        return switch (binary.op()) {
            case EQ -> Values.compare(left, right) == 0;
            case NE -> Values.compare(left, right) != 0;
            case LT -> Values.compare(left, right) < 0;
            case GT -> Values.compare(left, right) > 0;
            case LE -> Values.compare(left, right) <= 0;
            case GE -> Values.compare(left, right) >= 0;
            case ADD -> add(left, right);
            case SUB -> subtract(left, right);
            case MUL -> multiply(left, right);
            case DIV -> divide(left, right);
            case MOD -> modulo(left, right);
            case AND, OR, CONCAT -> throw new IllegalStateException();
        };
    }

    private Object logicAnd(Expr left, Expr right) {
        Object first = eval(left);
        if (first instanceof Boolean bool && !bool) {
            return false;
        }
        Object second = eval(right);
        if (!(first == null || first instanceof Boolean) || !(second == null || second instanceof Boolean)) {
            throw new NexException("AND exige expressões lógicas");
        }
        if (second instanceof Boolean bool && !bool) {
            return false;
        }
        if (first == null || second == null) {
            return null;
        }
        return true;
    }

    private Object logicOr(Expr left, Expr right) {
        Object first = eval(left);
        if (first instanceof Boolean bool && bool) {
            return true;
        }
        Object second = eval(right);
        if (!(first == null || first instanceof Boolean) || !(second == null || second instanceof Boolean)) {
            throw new NexException("OR exige expressões lógicas");
        }
        if (second instanceof Boolean bool && bool) {
            return true;
        }
        if (first == null || second == null) {
            return null;
        }
        return false;
    }

    private Object like(Like like) {
        Object value = eval(like.expr());
        Object pattern = eval(like.pattern());
        if (value == null || pattern == null) {
            return null;
        }
        if (!(value instanceof String text) || !(pattern instanceof String sample)) {
            throw new NexException("LIKE exige textos");
        }
        char escape = '\\';
        if (like.escape() != null) {
            Object raw = eval(like.escape());
            if (!(raw instanceof String escapeText) || escapeText.length() != 1) {
                throw new NexException("ESCAPE precisa ser um texto de um caractere");
            }
            escape = escapeText.charAt(0);
        }
        boolean matched = Values.like(text, sample, escape);
        return like.negated() ? !matched : matched;
    }

    private Object inList(InList inList) {
        Object value = eval(inList.expr());
        boolean found = false;
        boolean unknown = value == null;
        for (Expr candidate : inList.values()) {
            Object item = eval(candidate);
            if (item == null || value == null) {
                unknown = true;
                continue;
            }
            if (Values.compare(value, item) == 0) {
                found = true;
            }
        }
        if (found) {
            return !inList.negated();
        }
        if (unknown) {
            return null;
        }
        return inList.negated();
    }

    private Object between(Between between) {
        Object value = eval(between.expr());
        Object low = eval(between.low());
        Object high = eval(between.high());
        Boolean greater = compareNullable(value, low, true);
        Boolean lesser = compareNullable(value, high, false);
        Boolean combined = and(greater, lesser);
        if (combined == null) {
            return null;
        }
        return between.negated() ? !combined : combined;
    }

    private Boolean compareNullable(Object left, Object right, boolean lowerBound) {
        if (left == null || right == null) {
            return null;
        }
        int cmp = Values.compare(left, right);
        return lowerBound ? cmp >= 0 : cmp <= 0;
    }

    private Boolean and(Boolean left, Boolean right) {
        if (Boolean.FALSE.equals(left) || Boolean.FALSE.equals(right)) {
            return false;
        }
        if (left == null || right == null) {
            return null;
        }
        return true;
    }

    private Object call(Call call) {
        if (aggregate(call.name())) {
            throw new NexException(call.name() + " só pode ser usado na lista do SELECT");
        }
        String name = call.name().toUpperCase(Locale.ROOT);
        if (name.equals("GEN_ID")) {
            return genId(call);
        }
        return switch (name) {
            case "LOWER", "UPPER" -> changeCase(call, name.equals("UPPER"));
            case "LENGTH", "CHAR_LENGTH" -> length(call);
            case "COALESCE" -> coalesce(call);
            default -> throw new NexException("função desconhecida: " + call.name());
        };
    }

    private Object changeCase(Call call, boolean upper) {
        Object value = oneArg(call);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new NexException(call.name() + " espera texto");
        }
        return upper ? text.toUpperCase(Locale.ROOT) : text.toLowerCase(Locale.ROOT);
    }

    private Object length(Call call) {
        Object value = oneArg(call);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return (long) text.length();
        }
        if (value instanceof byte[] bytes) {
            return (long) bytes.length;
        }
        throw new NexException("LENGTH espera texto ou BLOB");
    }

    private Object coalesce(Call call) {
        if (call.args().isEmpty()) {
            throw new NexException("COALESCE precisa de ao menos um argumento");
        }
        for (Expr arg : call.args()) {
            Object value = eval(arg);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Object oneArg(Call call) {
        if (call.args().size() != 1 || call.star()) {
            throw new NexException(call.name() + " espera um argumento");
        }
        return eval(call.args().get(0));
    }

    private Object add(Object left, Object right) {
        if (floating(left, right)) {
            return Values.toDouble(left) + Values.toDouble(right);
        }
        if (decimal(left, right)) {
            return Values.asDecimal(left).add(Values.asDecimal(right));
        }
        return exactSum(left, right);
    }

    private Object subtract(Object left, Object right) {
        if (floating(left, right)) {
            return Values.toDouble(left) - Values.toDouble(right);
        }
        if (decimal(left, right)) {
            return Values.asDecimal(left).subtract(Values.asDecimal(right));
        }
        return exactDifference(left, right);
    }

    private Object multiply(Object left, Object right) {
        if (floating(left, right)) {
            return Values.toDouble(left) * Values.toDouble(right);
        }
        if (decimal(left, right)) {
            return Values.asDecimal(left).multiply(Values.asDecimal(right));
        }
        return exactProduct(left, right);
    }

    private Object divide(Object left, Object right) {
        if (decimal(left, right) && !(left instanceof Double) && !(right instanceof Double)
                && !(left instanceof Float) && !(right instanceof Float)) {
            BigDecimal divisor = Values.asDecimal(right);
            if (divisor.signum() == 0) {
                throw new NexException("divisão por zero");
            }
            return Values.asDecimal(left).divide(divisor, 8, RoundingMode.HALF_UP).stripTrailingZeros();
        }
        if (integral(left) && integral(right)) {
            if (wideInteger(left, right)) {
                long divisor = Values.toLong(right);
                if (divisor == 0) {
                    throw new NexException("divisão por zero");
                }
                return Values.toLong(left) / divisor;
            }
            int divisor = Values.toInt(right);
            if (divisor == 0) {
                throw new NexException("divisão por zero");
            }
            return Values.toInt(left) / divisor;
        }
        double divisor = Values.toDouble(right);
        if (divisor == 0) {
            throw new NexException("divisão por zero");
        }
        return Values.toDouble(left) / divisor;
    }

    private Object modulo(Object left, Object right) {
        if (!integral(left) || !integral(right)) {
            throw new NexException("módulo exige inteiros");
        }
        if (wideInteger(left, right)) {
            long divisor = Values.toLong(right);
            if (divisor == 0) {
                throw new NexException("divisão por zero");
            }
            return Values.toLong(left) % divisor;
        }
        int divisor = Values.toInt(right);
        if (divisor == 0) {
            throw new NexException("divisão por zero");
        }
        return Values.toInt(left) % divisor;
    }

    private static boolean wideInteger(Object left, Object right) {
        return left instanceof Long || right instanceof Long;
    }

    private static Object exactSum(Object left, Object right) {
        if (wideInteger(left, right)) {
            return Math.addExact(Values.toLong(left), Values.toLong(right));
        }
        return Math.addExact(Values.toInt(left), Values.toInt(right));
    }

    private static Object exactDifference(Object left, Object right) {
        if (wideInteger(left, right)) {
            return Math.subtractExact(Values.toLong(left), Values.toLong(right));
        }
        return Math.subtractExact(Values.toInt(left), Values.toInt(right));
    }

    private static Object exactProduct(Object left, Object right) {
        if (wideInteger(left, right)) {
            return Math.multiplyExact(Values.toLong(left), Values.toLong(right));
        }
        return Math.multiplyExact(Values.toInt(left), Values.toInt(right));
    }

    private static Object number(Object value, String operator) {
        if (integral(value) || value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
            return value;
        }
        throw new NexException(operator + " exige um número");
    }

    private static boolean floating(Object left, Object right) {
        return left instanceof Double || right instanceof Double || left instanceof Float || right instanceof Float;
    }

    private static boolean decimal(Object left, Object right) {
        return left instanceof BigDecimal || right instanceof BigDecimal;
    }

    private static boolean integral(Object value) {
        return value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte;
    }

    private static String text(Object value) {
        if (value instanceof byte[]) {
            throw new NexException("não é possível concatenar BLOB; converta antes");
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return String.valueOf(value);
    }
}
