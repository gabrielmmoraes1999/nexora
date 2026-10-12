package io.github.gabrielmmoraes1999.nexora;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class Column {
    final String name;
    final DataType type;
    final int length;
    final int scale;
    final boolean notNull;
    final boolean autoIncrement;
    final long nextIdentity;
    final boolean hasDefault;
    final Object defaultValue;

    Column(String name, DataType type, int length, int scale, boolean notNull, boolean autoIncrement,
           long nextIdentity, boolean hasDefault, Object defaultValue) {
        this.name = name;
        this.type = type;
        this.length = length;
        this.scale = scale;
        this.notNull = notNull;
        this.autoIncrement = autoIncrement;
        this.nextIdentity = nextIdentity;
        this.hasDefault = hasDefault;
        this.defaultValue = defaultValue;
    }

    Column withIdentity(long next) {
        return new Column(name, type, length, scale, notNull, autoIncrement, next, hasDefault, defaultValue);
    }

    Column asNotNull() {
        if (notNull) {
            return this;
        }
        return new Column(name, type, length, scale, true, autoIncrement, nextIdentity, hasDefault, defaultValue);
    }

    boolean heapBacked() {
        return type == DataType.BLOB
                || ((type == DataType.CHAR || type == DataType.VARCHAR) && length > Format.INLINE_CHAR);
    }

    boolean character() {
        return type == DataType.CHAR || type == DataType.VARCHAR;
    }
}

final class IndexDef {
    final String name;
    final int[] columns;
    final boolean unique;

    IndexDef(String name, int[] columns, boolean unique) {
        this.name = name;
        this.columns = columns.clone();
        this.unique = unique;
    }
}

final class Schema {
    final String name;
    final List<Column> columns;
    final int[] primaryKey;
    final List<int[]> uniques;
    final List<IndexDef> indexes;

    Schema(String name, List<Column> columns, int[] primaryKey, List<int[]> uniques, List<IndexDef> indexes) {
        this.name = name;
        this.columns = List.copyOf(columns);
        this.primaryKey = primaryKey.clone();
        this.uniques = uniques.stream().map(int[]::clone).toList();
        this.indexes = indexes.stream().map(index -> new IndexDef(index.name, index.columns, index.unique)).toList();
    }

    int indexOf(String columnName) {
        String folded = Names.fold(columnName);
        for (int i = 0; i < columns.size(); i++) {
            if (Names.fold(columns.get(i).name).equals(folded)) {
                return i;
            }
        }
        return -1;
    }

    Column column(String columnName) {
        int index = indexOf(columnName);
        if (index < 0) {
            throw new NexException("coluna não encontrada: " + columnName);
        }
        return columns.get(index);
    }

    Schema withIdentity(int columnIndex, long next) {
        List<Column> copy = new ArrayList<>(columns);
        copy.set(columnIndex, columns.get(columnIndex).withIdentity(next));
        return new Schema(name, copy, primaryKey, uniques, indexes);
    }

    Schema withPrimaryKey(int[] key) {
        if (key.length == 0) {
            throw new NexException("a chave primária precisa de ao menos uma coluna");
        }
        if (primaryKey.length > 0) {
            throw new NexException("a tabela " + name + " já possui chave primária");
        }
        List<Column> copy = new ArrayList<>(columns);
        for (int index : key) {
            copy.set(index, copy.get(index).asNotNull());
        }
        return new Schema(name, copy, key, uniques, indexes);
    }

    Schema add(Column column, boolean primary, boolean unique) {
        if (primary && primaryKey.length > 0) {
            throw new NexException("a tabela " + name + " já possui chave primária");
        }
        List<Column> copy = new ArrayList<>(columns);
        copy.add(column);
        int index = copy.size() - 1;
        int[] pk = primary ? new int[]{index} : primaryKey;
        List<int[]> groups = new ArrayList<>(uniques);
        if (unique && !primary) {
            groups.add(new int[]{index});
        }
        return new Schema(name, copy, pk, groups, indexes);
    }

    Schema withIndex(IndexDef index) {
        List<IndexDef> copy = new ArrayList<>(indexes);
        copy.add(index);
        return new Schema(name, columns, primaryKey, uniques, copy);
    }

    Schema withoutIndex(String indexName) {
        List<IndexDef> copy = new ArrayList<>();
        boolean found = false;
        for (IndexDef index : indexes) {
            if (Names.same(index.name, indexName)) {
                found = true;
                continue;
            }
            copy.add(index);
        }
        if (!found) {
            throw new NexException("índice não encontrado: " + indexName);
        }
        return new Schema(name, columns, primaryKey, uniques, copy);
    }

    Schema drop(int index) {
        List<Column> copy = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (i != index) {
                copy.add(columns.get(i));
            }
        }
        int[] primary = remap(primaryKey, index);
        List<int[]> unique = new ArrayList<>();
        for (int[] group : uniques) {
            if (contains(group, index)) {
                continue;
            }
            unique.add(remap(group, index));
        }
        List<IndexDef> kept = new ArrayList<>();
        for (IndexDef definition : indexes) {
            if (contains(definition.columns, index)) {
                continue;
            }
            kept.add(new IndexDef(definition.name, remap(definition.columns, index), definition.unique));
        }
        return new Schema(name, copy, primary, unique, kept);
    }

    private static int[] remap(int[] indexes, int removed) {
        int[] mapped = new int[indexes.length];
        int count = 0;
        for (int index : indexes) {
            if (index == removed) {
                continue;
            }
            mapped[count++] = index > removed ? index - 1 : index;
        }
        return Arrays.copyOf(mapped, count);
    }

    private static boolean contains(int[] indexes, int target) {
        for (int index : indexes) {
            if (index == target) {
                return true;
            }
        }
        return false;
    }
}

final class IndexKey {
    private final Object[] parts;
    private final int hash;

    IndexKey(Object[] parts) {
        this.parts = parts;
        this.hash = Arrays.deepHashCode(parts);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IndexKey key && Arrays.deepEquals(parts, key.parts);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return Arrays.toString(parts);
    }
}

final class Names {
    private Names() {
    }

    static String fold(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    static boolean same(String left, String right) {
        return left != null && right != null && fold(left).equals(fold(right));
    }

    static void checkName(String name, String kind) {
        if (name == null || name.isBlank()) {
            throw new NexException(kind + " é obrigatório");
        }
        if (name.length() > Format.MAX_NAME) {
            throw new NexException(kind + " excede " + Format.MAX_NAME + " caracteres");
        }
    }
}
