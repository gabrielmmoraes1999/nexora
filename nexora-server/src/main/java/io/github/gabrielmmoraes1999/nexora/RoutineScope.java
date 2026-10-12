package io.github.gabrielmmoraes1999.nexora;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Variáveis de uma procedure ou trigger, mais as linhas {@code NEW} e {@code OLD}.
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
            throw new NexException("variável duplicada: " + param.name());
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
            throw new NexException("variável não declarada: " + name);
        }
        return values.get(key);
    }

    void setVariable(String name, Object value) {
        String key = Names.fold(name);
        Column column = types.get(key);
        if (column == null) {
            throw new NexException("variável não declarada: " + name);
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
            throw new NexException(qualifier.toUpperCase() + " não existe neste contexto");
        }
        int index = rowSchema.indexOf(name);
        if (index < 0) {
            throw new NexException("coluna não encontrada: " + name);
        }
        Object value = row[index];
        return value == Values.MISSING ? null : value;
    }

    void setNew(String name, Object value) {
        if (newValues == null || rowSchema == null) {
            throw new NexException("NEW não existe neste contexto");
        }
        int index = rowSchema.indexOf(name);
        if (index < 0) {
            throw new NexException("coluna não encontrada: " + name);
        }
        Column column = rowSchema.columns.get(index);
        newValues[index] = value == null ? null : Values.coerce(column.type, column.length, column.scale, value);
    }
}
