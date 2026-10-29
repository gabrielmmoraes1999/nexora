package io.github.gabrielmmoraes1999.seadb;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Variables of a procedure or trigger, plus the {@code NEW} and {@code OLD} rows.
 */
final class RoutineScope {

    final Map<String, Column> types = new LinkedHashMap<>();
    final Map<String, Object> values = new LinkedHashMap<>();
    Schema rowSchema;
    Object[] newValues;
    Object[] oldValues;
    boolean preferVariables;
    long rowCount;

    void declare(ParamDef param, Object initial) {
        String key = Names.fold(param.name());
        if (types.containsKey(key)) {
            throw new SeaException("duplicate variable: " + param.name());
        }
        Column column = new Column(param.name(), param.type(), param.length(), param.scale(), false, false, 0L, false, null);
        types.put(key, column);
        values.put(key, initial == null ? null : Values.coerce(column.type, column.length, column.scale, initial));
    }

    boolean hasVariable(String name) {
        return types.containsKey(Names.fold(name));
    }

    DataType typeOf(String name) {
        Column column = types.get(Names.fold(name));
        return column == null ? DataType.VARCHAR : column.type;
    }

    Object variable(String name) {
        String key = Names.fold(name);
        if (!types.containsKey(key)) {
            throw new SeaException("undeclared variable: " + name);
        }
        return values.get(key);
    }

    void setVariable(String name, Object value) {
        String key = Names.fold(name);
        Column column = types.get(key);
        if (column == null) {
            throw new SeaException("undeclared variable: " + name);
        }
        values.put(key, value == null ? null : Values.coerce(column.type, column.length, column.scale, value));
    }

    void bindRow(Schema schema, Object[] newValues, Object[] oldValues) {
        this.rowSchema = schema;
        this.newValues = newValues;
        this.oldValues = oldValues;
    }

    Object field(String qualifier, String name) {
        Object[] row = Names.same(qualifier, "NEW") ? newValues : oldValues;
        if (row == null || rowSchema == null) {
            throw new SeaException(qualifier.toUpperCase() + " does not exist in this context");
        }
        int index = rowSchema.indexOf(name);
        if (index < 0) {
            throw new SeaException("column not found: " + name);
        }
        Object value = row[index];
        return value == Values.MISSING ? null : value;
    }

    void setNew(String name, Object value) {
        if (newValues == null || rowSchema == null) {
            throw new SeaException("NEW does not exist in this context");
        }
        int index = rowSchema.indexOf(name);
        if (index < 0) {
            throw new SeaException("column not found: " + name);
        }
        Column column = rowSchema.columns.get(index);
        newValues[index] = value == null ? null : Values.coerce(column.type, column.length, column.scale, value);
    }
}
