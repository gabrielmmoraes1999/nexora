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
    final boolean descending;

    IndexDef(String name, int[] columns, boolean unique) {
        this(name, columns, unique, false);
    }

    IndexDef(String name, int[] columns, boolean unique, boolean descending) {
        this.name = name;
        this.columns = columns.clone();
        this.unique = unique;
        this.descending = descending;
    }
}

final class UniqueKey {
    final String name;
    final int[] columns;

    UniqueKey(String name, int[] columns) {
        this.name = name;
        this.columns = columns.clone();
    }

    UniqueKey withColumns(int[] columns) {
        return new UniqueKey(name, columns);
    }
}

final class ForeignKey {
    final String name;
    final int[] columns;
    final String referencedSchema;
    final String referencedTable;
    final String[] referencedColumns;
    final String indexName;
    final boolean descending;
    final ReferentialAction onUpdate;
    final ReferentialAction onDelete;

    ForeignKey(String name, int[] columns, String referencedSchema, String referencedTable,
               String[] referencedColumns, String indexName, boolean descending,
               ReferentialAction onUpdate, ReferentialAction onDelete) {
        this.name = name;
        this.columns = columns.clone();
        this.referencedSchema = referencedSchema;
        this.referencedTable = referencedTable;
        this.referencedColumns = referencedColumns.clone();
        this.indexName = indexName;
        this.descending = descending;
        this.onUpdate = onUpdate == null ? ReferentialAction.NO_ACTION : onUpdate;
        this.onDelete = onDelete == null ? ReferentialAction.NO_ACTION : onDelete;
    }

    ForeignKey withColumns(int[] columns) {
        return new ForeignKey(name, columns, referencedSchema, referencedTable, referencedColumns, indexName, descending,
                onUpdate, onDelete);
    }
}

final class Schema {
    final String name;
    final List<Column> columns;
    final int[] primaryKey;
    final List<UniqueKey> uniques;
    final List<IndexDef> indexes;
    final String primaryConstraint;
    final String primaryIndex;
    final boolean primaryDescending;
    final List<ForeignKey> foreignKeys;

    Schema(String name, List<Column> columns, int[] primaryKey, List<UniqueKey> uniques, List<IndexDef> indexes) {
        this(name, columns, primaryKey, uniques, indexes,
                primaryKey.length == 0 ? "" : "PK_" + name,
                primaryKey.length == 0 ? "" : "PK_" + name,
                false,
                List.of());
    }

    Schema(String name, List<Column> columns, int[] primaryKey, List<UniqueKey> uniques, List<IndexDef> indexes,
           String primaryConstraint, String primaryIndex, boolean primaryDescending, List<ForeignKey> foreignKeys) {
        this.name = name;
        this.columns = List.copyOf(columns);
        this.primaryKey = primaryKey.clone();
        this.uniques = uniques.stream().map(key -> new UniqueKey(key.name, key.columns)).toList();
        this.indexes = indexes.stream()
                .map(index -> new IndexDef(index.name, index.columns, index.unique, index.descending))
                .toList();
        if (this.primaryKey.length == 0) {
            this.primaryConstraint = "";
            this.primaryIndex = "";
            this.primaryDescending = false;
        } else {
            this.primaryConstraint = primaryConstraint == null || primaryConstraint.isBlank() ? "PK_" + name : primaryConstraint;
            this.primaryIndex = primaryIndex == null || primaryIndex.isBlank() ? this.primaryConstraint : primaryIndex;
            this.primaryDescending = primaryDescending;
        }
        this.foreignKeys = foreignKeys.stream()
                .map(key -> new ForeignKey(key.name, key.columns, key.referencedSchema, key.referencedTable,
                        key.referencedColumns, key.indexName, key.descending, key.onUpdate, key.onDelete))
                .toList();
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
            throw new NexException("column not found: " + columnName);
        }
        return columns.get(index);
    }

    Schema withIdentity(int columnIndex, long next) {
        List<Column> copy = new ArrayList<>(columns);
        copy.set(columnIndex, columns.get(columnIndex).withIdentity(next));
        return copy(copy, primaryKey, uniques, indexes, primaryConstraint, primaryIndex, primaryDescending, foreignKeys);
    }

    Schema withPrimaryKey(int[] key, String constraint, String index, boolean descending) {
        if (key.length == 0) {
            throw new NexException("primary key requires at least one column");
        }
        if (primaryKey.length > 0) {
            throw new NexException("table " + name + " already has a primary key");
        }
        List<Column> copy = new ArrayList<>(columns);
        for (int column : key) {
            copy.set(column, copy.get(column).asNotNull());
        }
        return copy(copy, key, uniques, indexes, constraint, index, descending, foreignKeys);
    }

    Schema add(Column column, boolean primary, String uniqueName) {
        if (primary && primaryKey.length > 0) {
            throw new NexException("table " + name + " already has a primary key");
        }
        List<Column> copy = new ArrayList<>(columns);
        copy.add(column);
        int index = copy.size() - 1;
        int[] pk = primary ? new int[]{index} : primaryKey;
        List<UniqueKey> groups = new ArrayList<>(uniques);
        if (uniqueName != null && !primary) {
            groups.add(new UniqueKey(uniqueName, new int[]{index}));
        }
        String constraint = primaryConstraint;
        String indexName = primaryIndex;
        boolean descending = primaryDescending;
        if (primary) {
            constraint = "PK_" + name;
            indexName = constraint;
            descending = false;
        }
        return copy(copy, pk, groups, indexes, constraint, indexName, descending, foreignKeys);
    }

    Schema namePrimary(String constraint, String index, boolean descending) {
        if (primaryKey.length == 0) {
            throw new NexException("table " + name + " does not have a primary key");
        }
        return copy(columns, primaryKey, uniques, indexes, constraint, index, descending, foreignKeys);
    }

    Schema withIndex(IndexDef index) {
        List<IndexDef> copy = new ArrayList<>(indexes);
        copy.add(index);
        return copy(columns, primaryKey, uniques, copy, primaryConstraint, primaryIndex, primaryDescending, foreignKeys);
    }

    Schema withUnique(UniqueKey key) {
        List<UniqueKey> copy = new ArrayList<>(uniques);
        copy.add(key);
        return copy(columns, primaryKey, copy, indexes, primaryConstraint, primaryIndex, primaryDescending, foreignKeys);
    }

    Schema withoutUnique(String constraint) {
        List<UniqueKey> copy = new ArrayList<>();
        boolean found = false;
        for (UniqueKey key : uniques) {
            if (!found && Names.same(key.name, constraint)) {
                found = true;
                continue;
            }
            copy.add(key);
        }
        if (!found) {
            throw new NexException("unique key not found: " + constraint);
        }
        return copy(columns, primaryKey, copy, indexes, primaryConstraint, primaryIndex, primaryDescending, foreignKeys);
    }

    Schema withForeignKey(ForeignKey key) {
        List<ForeignKey> copy = new ArrayList<>(foreignKeys);
        copy.add(key);
        return copy(columns, primaryKey, uniques, indexes, primaryConstraint, primaryIndex, primaryDescending, copy);
    }

    Schema withoutPrimaryKey() {
        if (primaryKey.length == 0) {
            throw new NexException("table " + name + " does not have a primary key");
        }
        return copy(columns, new int[0], uniques, indexes, "", "", false, foreignKeys);
    }

    Schema withoutForeignKey(String constraint) {
        ForeignKey found = null;
        List<ForeignKey> keys = new ArrayList<>();
        for (ForeignKey key : foreignKeys) {
            if (found == null && Names.same(key.name, constraint)) {
                found = key;
                continue;
            }
            keys.add(key);
        }
        if (found == null) {
            throw new NexException("constraint not found: " + constraint);
        }
        List<IndexDef> kept = new ArrayList<>();
        boolean removed = false;
        for (IndexDef index : indexes) {
            if (!removed && Names.same(index.name, found.indexName)) {
                removed = true;
                continue;
            }
            kept.add(index);
        }
        if (!removed) {
            throw new NexException("index not found: " + found.indexName);
        }
        return copy(columns, primaryKey, uniques, kept, primaryConstraint, primaryIndex, primaryDescending, keys);
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
            throw new NexException("index not found: " + indexName);
        }
        return copy(columns, primaryKey, uniques, copy, primaryConstraint, primaryIndex, primaryDescending, foreignKeys);
    }

    /**
     * Moves the column at {@code from} so it occupies {@code to}. Both indexes start at 0.
     * Primary keys, unique keys, indexes, and foreign keys keep the same columns.
     */
    Schema move(int from, int to) {
        if (from == to) {
            return this;
        }
        List<Column> copy = new ArrayList<>(columns);
        Column moved = copy.remove(from);
        copy.add(to, moved);
        List<UniqueKey> unique = new ArrayList<>();
        for (UniqueKey key : uniques) {
            unique.add(key.withColumns(remapMove(key.columns, from, to)));
        }
        List<IndexDef> kept = new ArrayList<>();
        for (IndexDef definition : indexes) {
            kept.add(new IndexDef(definition.name, remapMove(definition.columns, from, to),
                    definition.unique, definition.descending));
        }
        List<ForeignKey> keys = new ArrayList<>();
        for (ForeignKey key : foreignKeys) {
            keys.add(key.withColumns(remapMove(key.columns, from, to)));
        }
        return copy(copy, remapMove(primaryKey, from, to), unique, kept,
                primaryConstraint, primaryIndex, primaryDescending, keys);
    }

    static int movedIndex(int index, int from, int to) {
        if (index == from) {
            return to;
        }
        if (from < to) {
            return index > from && index <= to ? index - 1 : index;
        }
        return index >= to && index < from ? index + 1 : index;
    }

    Schema drop(int index) {
        List<Column> copy = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (i != index) {
                copy.add(columns.get(i));
            }
        }
        int[] primary = remap(primaryKey, index);
        List<UniqueKey> unique = new ArrayList<>();
        for (UniqueKey key : uniques) {
            if (contains(key.columns, index)) {
                continue;
            }
            unique.add(key.withColumns(remap(key.columns, index)));
        }
        List<IndexDef> kept = new ArrayList<>();
        for (IndexDef definition : indexes) {
            if (contains(definition.columns, index)) {
                continue;
            }
            kept.add(new IndexDef(definition.name, remap(definition.columns, index), definition.unique, definition.descending));
        }
        List<ForeignKey> keys = new ArrayList<>();
        for (ForeignKey key : foreignKeys) {
            if (contains(key.columns, index)) {
                continue;
            }
            keys.add(key.withColumns(remap(key.columns, index)));
        }
        String constraint = primary.length == 0 ? "" : primaryConstraint;
        String indexName = primary.length == 0 ? "" : primaryIndex;
        boolean descending = primary.length > 0 && primaryDescending;
        return copy(copy, primary, unique, kept, constraint, indexName, descending, keys);
    }

    boolean ownsIndex(String indexName) {
        if (primaryKey.length > 0 && Names.same(primaryIndex, indexName)) {
            return true;
        }
        for (ForeignKey key : foreignKeys) {
            if (Names.same(key.indexName, indexName)) {
                return true;
            }
        }
        for (UniqueKey key : uniques) {
            if (Names.same(key.name, indexName)) {
                return true;
            }
        }
        return false;
    }

    private Schema copy(List<Column> columns, int[] primaryKey, List<UniqueKey> uniques, List<IndexDef> indexes,
                        String primaryConstraint, String primaryIndex, boolean primaryDescending, List<ForeignKey> foreignKeys) {
        return new Schema(name, columns, primaryKey, uniques, indexes, primaryConstraint, primaryIndex, primaryDescending, foreignKeys);
    }

    private static int[] remapMove(int[] indexes, int from, int to) {
        int[] mapped = new int[indexes.length];
        for (int i = 0; i < indexes.length; i++) {
            mapped[i] = movedIndex(indexes[i], from, to);
        }
        return mapped;
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

    /** Columns, keys, and indexes. Identity counters may differ. */
    boolean sameStructure(Schema other) {
        if (other == this) {
            return true;
        }
        if (other == null || columns.size() != other.columns.size()) {
            return false;
        }
        for (int i = 0; i < columns.size(); i++) {
            Column one = columns.get(i);
            Column two = other.columns.get(i);
            if (!Names.same(one.name, two.name) || one.type != two.type || one.length != two.length
                    || one.scale != two.scale || one.notNull != two.notNull || one.autoIncrement != two.autoIncrement
                    || one.hasDefault != two.hasDefault || !sameDefault(one.defaultValue, two.defaultValue)) {
                return false;
            }
        }
        if (!Arrays.equals(primaryKey, other.primaryKey) || primaryDescending != other.primaryDescending
                || !Names.same(primaryConstraint, other.primaryConstraint) || !Names.same(primaryIndex, other.primaryIndex)) {
            return false;
        }
        if (uniques.size() != other.uniques.size() || indexes.size() != other.indexes.size()
                || foreignKeys.size() != other.foreignKeys.size()) {
            return false;
        }
        for (int i = 0; i < uniques.size(); i++) {
            UniqueKey one = uniques.get(i);
            UniqueKey two = other.uniques.get(i);
            if (!Names.same(one.name, two.name) || !Arrays.equals(one.columns, two.columns)) {
                return false;
            }
        }
        for (int i = 0; i < indexes.size(); i++) {
            IndexDef one = indexes.get(i);
            IndexDef two = other.indexes.get(i);
            if (!Names.same(one.name, two.name) || one.unique != two.unique || one.descending != two.descending
                    || !Arrays.equals(one.columns, two.columns)) {
                return false;
            }
        }
        for (int i = 0; i < foreignKeys.size(); i++) {
            ForeignKey one = foreignKeys.get(i);
            ForeignKey two = other.foreignKeys.get(i);
            if (!Names.same(one.name, two.name) || !Names.same(one.indexName, two.indexName)
                    || !Names.same(one.referencedSchema, two.referencedSchema)
                    || !Names.same(one.referencedTable, two.referencedTable)
                    || one.descending != two.descending || one.onUpdate != two.onUpdate || one.onDelete != two.onDelete
                    || !Arrays.equals(one.columns, two.columns)
                    || !Arrays.equals(one.referencedColumns, two.referencedColumns)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameDefault(Object left, Object right) {
        if (left instanceof byte[] one && right instanceof byte[] two) {
            return Arrays.equals(one, two);
        }
        return java.util.Objects.equals(left, right);
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
            throw new NexException(kind + " is required");
        }
        if (name.length() > Format.MAX_NAME) {
            throw new NexException(kind + " exceeds " + Format.MAX_NAME + " characters");
        }
    }

    /** Generated UNIQUE name: {@code UNQ1_table}. */
    static boolean generatedUnique(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String folded = fold(name);
        if (!folded.startsWith("unq")) {
            return false;
        }
        int split = folded.indexOf('_');
        if (split <= 3) {
            return false;
        }
        return digits(folded.substring(3, split)) && !folded.substring(split + 1).isEmpty();
    }

    private static boolean digits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
