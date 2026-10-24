package io.github.gabrielmmoraes1999.nexora;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A table inside the database file. Offsets are relative
 * to the start of the region. The BLOB lives in that same region. Commit writes the
 * new data and only then swaps the header pointer. The space
 * The index also lives in that region, in pages. The search reads those pages;
 * the key list does not stay in memory after commit.
 */
final class TableStore implements AutoCloseable {

    private final DatabaseFile file;
    private long base;
    private long capacity;
    private Schema schema;
    private String namespace = Relation.PUBLIC;
    private TreeMap<Long, StoredRow> rows = new TreeMap<>();
    private List<Extent> freeExtents = new ArrayList<>();
    private List<Extent> pendingFree = new ArrayList<>();
    private long fileSize;
    private long nextRowId = 1;
    private long metaOffset;
    private int metaLength;
    private long generation;
    private int activeSlot;
    private boolean dirty;
    /**
     * The live map is what a committed savepoint still sees.
     * The next change copies the map before modifying it.
     */
    private boolean shared;
    /**
     * Savepoint that still points at the live map. Ids inserted in it
     * are undone if that savepoint is restored.
     */
    private Snapshot undo;
    private List<IndexTree.DiskIndex> indexes = List.of();

    private TableStore(DatabaseFile file, long base, long capacity) {
        this.file = file;
        this.base = base;
        this.capacity = capacity;
    }

    static TableStore create(DatabaseFile file, long base, long capacity, Schema schema) {
        validate(schema);
        TableStore store = new TableStore(file, base, capacity);
        store.initNew(schema);
        return store;
    }

    static TableStore open(DatabaseFile file, DatabaseFile.StoredTable region) {
        TableStore store = new TableStore(file, region.base(), region.capacity());
        store.reload();
        return store;
    }

    long base() {
        return base;
    }

    long capacity() {
        return capacity;
    }

    long logicalSize() {
        return fileSize;
    }

    long nextRowId() {
        return nextRowId;
    }

    void retarget(long base, long capacity) {
        this.base = base;
        this.capacity = capacity;
    }

    Schema schema() {
        return schema;
    }

    String namespace() {
        return namespace;
    }

    void namespace(String namespace) {
        this.namespace = namespace;
    }

    boolean isDirty() {
        return dirty;
    }

    List<StoredRow> scan() {
        return new ArrayList<>(rows.values());
    }

    /** Column values of a row, including bytes that still live in the table region. */
    Object[] copyValues(Schema shape, StoredRow row) {
        Object[] values = new Object[shape.columns.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = detached(logical(row.cells[i], shape.columns.get(i)));
        }
        return values;
    }

    /**
     * Puts {@code id} back. An existing row is overwritten, so recovery can apply the same undo twice.
     */
    void restoreRow(long id, Object[] raw) {
        if (raw.length != schema.columns.size()) {
            throw new NexException("value count does not match the columns of " + schema.name);
        }
        Cell[] cells = new Cell[raw.length];
        for (int i = 0; i < raw.length; i++) {
            cells[i] = toCell(schema.columns.get(i), raw[i]);
        }
        if (rows.containsKey(id)) {
            update(java.util.Map.of(id, cells));
            return;
        }
        unshare();
        StoredRow row = new StoredRow(id, cells, -1, 0, true);
        rows.put(id, row);
        if (id >= nextRowId) {
            nextRowId = id + 1;
        }
        indexInserted(row);
        dirty = true;
    }

    StoredRow row(long id) {
        return rows.get(id);
    }

    boolean hasIndex(String name) {
        if (schema.ownsIndex(name)) {
            return true;
        }
        for (IndexDef index : schema.indexes) {
            if (Names.same(index.name, name)) {
                return true;
            }
        }
        return false;
    }

    void createIndex(String name, int[] columns, boolean unique) {
        rejectBlobKey(schema, columns);
        Schema next = schema.withIndex(new IndexDef(name, columns, unique));
        validate(next);
        IndexTree.DiskIndex created = IndexTree.DiskIndex.empty(new IndexTree.Spec(name, columns, false, unique))
                .fill(schema, rows.values(), this::keyParts, new ArrayList<>(), pages());
        schema = next;
        List<IndexTree.DiskIndex> copy = new ArrayList<>(indexes);
        copy.add(created);
        indexes = copy;
        dirty = true;
    }

    void dropIndex(String name) {
        Schema next = schema.withoutIndex(name);
        validate(next);
        releaseIndex(name);
        schema = next;
        dirty = true;
    }

    void addUnique(UniqueKey key) {
        if (hasIndex(key.name)) {
            throw new NexException("index already exists: " + key.name);
        }
        rejectBlobKey(schema, key.columns);
        Schema next = schema.withUnique(key);
        validate(next);
        List<Extent> extra = new ArrayList<>();
        IndexTree.DiskIndex created = IndexTree.DiskIndex.empty(new IndexTree.Spec(key.name, key.columns, false, true))
                .fill(next, rows.values(), this::keyParts, extra, pages());
        int at = (schema.primaryKey.length > 0 ? 1 : 0) + schema.uniques.size();
        List<IndexTree.DiskIndex> copy = new ArrayList<>();
        copy.addAll(indexes.subList(0, at));
        copy.add(created);
        if (at < indexes.size()) {
            copy.addAll(indexes.subList(at, indexes.size()));
        }
        schema = next;
        indexes = copy;
        pendingFree.addAll(extra);
        dirty = true;
    }

    void dropUnique(String name) {
        boolean found = false;
        for (UniqueKey key : schema.uniques) {
            if (Names.same(key.name, name)) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new NexException("unique key not found: " + name);
        }
        Schema next = schema.withoutUnique(name);
        validate(next);
        releaseIndex(name);
        schema = next;
        dirty = true;
    }

    void dropPrimaryKey() {
        if (schema.primaryKey.length == 0) {
            throw new NexException("table " + schema.name + " does not have a primary key");
        }
        String indexName = schema.primaryIndex;
        Schema next = schema.withoutPrimaryKey();
        validate(next);
        releaseIndex(indexName);
        schema = next;
        dirty = true;
    }

    void dropForeignKey(String name) {
        String indexName = null;
        for (ForeignKey key : schema.foreignKeys) {
            if (Names.same(key.name, name)) {
                indexName = key.indexName;
                break;
            }
        }
        if (indexName == null) {
            throw new NexException("foreign key not found: " + name);
        }
        Schema next = schema.withoutForeignKey(name);
        validate(next);
        releaseIndex(indexName);
        schema = next;
        dirty = true;
    }

    private void releaseIndex(String name) {
        List<IndexTree.DiskIndex> kept = new ArrayList<>();
        IndexTree.DiskIndex removed = null;
        for (IndexTree.DiskIndex index : indexes) {
            if (removed == null && Names.same(index.name, name)) {
                removed = index;
                continue;
            }
            kept.add(index);
        }
        if (removed == null) {
            throw new NexException("index not found: " + name);
        }
        removed.release(pendingFree, pages());
        indexes = kept;
    }

    /**
     * True when some index has a leftmost prefix inside {@code columns}.
     * The values are not required; this only decides whether a seek is possible.
     */
    boolean canSeek(Set<Integer> columns) {
        if (columns.isEmpty()) {
            return false;
        }
        Map<Integer, Object> marker = new HashMap<>();
        for (Integer column : columns) {
            marker.put(column, Boolean.TRUE);
        }
        return chooseIndex(marker) != null;
    }

    List<StoredRow> lookup(Map<Integer, Object> equals) {
        if (equals.isEmpty()) {
            return null;
        }
        IndexTree.DiskIndex best = chooseIndex(equals);
        if (best == null) {
            return null;
        }
        int prefix = best.prefixLength(equals);
        Object[] parts = new Object[prefix];
        for (int i = 0; i < prefix; i++) {
            Object value = equals.get(best.columns[i]);
            if (value == null) {
                return List.of();
            }
            parts[i] = value;
        }
        List<Long> ids = prefix == best.columns.length
                ? best.find(schema, parts, pages())
                : best.findPrefix(schema, prefix, parts, pages());
        List<StoredRow> found = new ArrayList<>();
        for (Long id : ids) {
            StoredRow row = rows.get(id);
            if (row != null) {
                found.add(row);
            }
        }
        found.sort((left, right) -> Long.compare(left.id, right.id));
        return found;
    }

    private IndexTree.DiskIndex chooseIndex(Map<Integer, Object> equals) {
        IndexTree.DiskIndex best = null;
        int bestPrefix = 0;
        for (IndexTree.DiskIndex index : indexes) {
            int prefix = index.prefixLength(equals);
            if (prefix == 0) {
                continue;
            }
            boolean exact = prefix == index.columns.length;
            boolean bestExact = best != null && bestPrefix == best.columns.length;
            if (best == null || prefix > bestPrefix || (prefix == bestPrefix && exact && !bestExact)) {
                best = index;
                bestPrefix = prefix;
            }
        }
        return best;
    }

    Object value(StoredRow row, int index) {
        return logical(row.cells[index], schema.columns.get(index));
    }

    long insertOne(Object[] raw) {
        long id = nextRowId;
        insert(java.util.Collections.singletonList(raw));
        return id;
    }

    int insert(List<Object[]> batch) {
        int count = 0;
        for (Object[] raw : batch) {
            if (raw.length != schema.columns.size()) {
                throw new NexException("value count does not match the columns of " + schema.name);
            }
            Cell[] cells = new Cell[raw.length];
            for (int i = 0; i < raw.length; i++) {
                Column column = schema.columns.get(i);
                Object value = raw[i];
                if (value == Values.MISSING) {
                    if (column.autoIncrement) {
                        value = column.nextIdentity;
                        schema = schema.withIdentity(i, column.nextIdentity + 1);
                        column = schema.columns.get(i);
                    } else if (column.hasDefault) {
                        value = column.defaultValue;
                    } else {
                        value = null;
                    }
                } else if (column.autoIncrement && value instanceof Number number
                        && number.longValue() >= column.nextIdentity) {
                    schema = schema.withIdentity(i, number.longValue() + 1);
                    column = schema.columns.get(i);
                }
                if (value == null && column.notNull) {
                    throw new NexException("required column has no value: " + schema.name + "." + column.name);
                }
                cells[i] = toCell(column, value);
            }
            long id = nextRowId++;
            StoredRow row = new StoredRow(id, cells, -1, 0, true);
            unshare();
            rows.put(id, row);
            if (undo != null && undo.rows == rows) {
                undo.inserted.add(id);
            }
            indexInserted(row);
            dirty = true;
            count++;
        }
        return count;
    }

    void update(Map<Long, Cell[]> replacements) {
        if (replacements.isEmpty()) {
            return;
        }
        unshare();
        Schema nextSchema = schema;
        TreeMap<Long, StoredRow> next = new TreeMap<>(rows);
        List<Extent> freed = new ArrayList<>();
        List<Extent> extra = new ArrayList<>();
        for (Map.Entry<Long, Cell[]> entry : replacements.entrySet()) {
            StoredRow current = rows.get(entry.getKey());
            if (current == null) {
                throw new NexException("record not found for update");
            }
            Cell[] cells = entry.getValue();
            if (cells.length != schema.columns.size()) {
                throw new NexException("value count does not match the columns of " + schema.name);
            }
            for (int i = 0; i < cells.length; i++) {
                Column column = nextSchema.columns.get(i);
                if (cells[i].isNull() && column.notNull) {
                    throw new NexException("required column has no value: " + schema.name + "." + column.name);
                }
                if (column.autoIncrement && cells[i].value instanceof Number number
                        && number.longValue() >= column.nextIdentity) {
                    nextSchema = nextSchema.withIdentity(i, number.longValue() + 1);
                }
            }
            freed.addAll(freedExtents(current, cells));
            StoredRow replacement = new StoredRow(current.id, cells, -1, 0, true);
            reindex(schema, current, nextSchema, replacement, extra);
            next.put(current.id, replacement);
        }
        schema = nextSchema;
        rows = next;
        pendingFree.addAll(freed);
        pendingFree.addAll(extra);
        dirty = true;
    }

    int delete(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        unshare();
        TreeMap<Long, StoredRow> next = new TreeMap<>(rows);
        List<Extent> freed = new ArrayList<>();
        List<Extent> extra = new ArrayList<>();
        int count = 0;
        for (Long id : ids) {
            StoredRow current = next.remove(id);
            if (current == null) {
                continue;
            }
            unindex(current, extra);
            freed.addAll(freedExtents(current, null));
            count++;
        }
        rows = next;
        pendingFree.addAll(freed);
        pendingFree.addAll(extra);
        dirty = true;
        return count;
    }

    void addColumn(Column column, boolean primary, String uniqueName) {
        if (schema.indexOf(column.name) >= 0) {
            throw new NexException("column already exists: " + column.name);
        }
        if ((primary || uniqueName != null) && column.type == DataType.BLOB) {
            throw new NexException("BLOB cannot be a key");
        }
        if (!rows.isEmpty() && column.notNull && !column.hasDefault && !column.autoIncrement) {
            throw new NexException("NOT NULL column requires a DEFAULT when the table already has records");
        }
        Column stored = column.autoIncrement && column.nextIdentity == 0 ? column.withIdentity(1) : column;
        long identity = stored.nextIdentity;
        TreeMap<Long, StoredRow> next = new TreeMap<>();
        List<Extent> freed = new ArrayList<>();
        for (StoredRow current : rows.values()) {
            Cell[] cells = new Cell[current.cells.length + 1];
            System.arraycopy(current.cells, 0, cells, 0, current.cells.length);
            Object value = null;
            if (!rows.isEmpty() && stored.autoIncrement && !stored.hasDefault) {
                value = identity++;
            } else if (stored.hasDefault) {
                value = stored.defaultValue;
            }
            if (value == null && stored.notNull) {
                throw new NexException("required column has no value: " + stored.name);
            }
            cells[cells.length - 1] = toCell(stored, value);
            freed.addAll(freedExtents(current, cells));
            next.put(current.id, new StoredRow(current.id, cells, -1, 0, true));
        }
        if (stored.autoIncrement) {
            stored = stored.withIdentity(identity);
        }
        Schema nextSchema = schema.add(stored, primary, uniqueName);
        validate(nextSchema);
        indexes = indexesAfterAdd(nextSchema, next, primary);
        schema = nextSchema;
        rows = next;
        pendingFree.addAll(freed);
        dirty = true;
    }

    void addPrimaryKey(int[] columns, String constraint, String index, boolean descending) {
        if (hasIndex(index)) {
            throw new NexException("index already exists: " + index);
        }
        List<Extent> extra = new ArrayList<>();
        IndexTree.DiskIndex primary = IndexTree.DiskIndex.empty(new IndexTree.Spec(index, columns, true, true))
                .fill(schema, rows.values(), this::keyParts, extra, pages());
        Schema nextSchema = schema.withPrimaryKey(columns, constraint, index, descending);
        validate(nextSchema);
        List<IndexTree.DiskIndex> copy = new ArrayList<>();
        copy.add(primary);
        copy.addAll(indexes);
        schema = nextSchema;
        indexes = copy;
        pendingFree.addAll(extra);
        dirty = true;
    }

    void addForeignKey(ForeignKey key) {
        if (hasIndex(key.indexName)) {
            throw new NexException("index already exists: " + key.indexName);
        }
        rejectBlobKey(schema, key.columns);
        Schema next = schema.withIndex(new IndexDef(key.indexName, key.columns, false, key.descending)).withForeignKey(key);
        validate(next);
        IndexTree.DiskIndex created = IndexTree.DiskIndex.empty(new IndexTree.Spec(key.indexName, key.columns, false, false))
                .fill(schema, rows.values(), this::keyParts, new ArrayList<>(), pages());
        schema = next;
        List<IndexTree.DiskIndex> copy = new ArrayList<>(indexes);
        copy.add(created);
        indexes = copy;
        dirty = true;
    }

    void moveColumn(String name, int position, Runnable beforeMove) {
        int from = schema.indexOf(name);
        if (from < 0) {
            throw new NexException("column not found: " + name);
        }
        int count = schema.columns.size();
        if (position < 1 || position > count) {
            throw new NexException("column position must be between 1 and " + count);
        }
        int to = position - 1;
        if (from == to) {
            return;
        }
        if (beforeMove != null) {
            beforeMove.run();
        }
        Schema nextSchema = schema.move(from, to);
        validate(nextSchema);
        TreeMap<Long, StoredRow> next = new TreeMap<>();
        List<Extent> freed = new ArrayList<>();
        for (StoredRow current : rows.values()) {
            Cell[] cells = new Cell[current.cells.length];
            for (int i = 0; i < current.cells.length; i++) {
                cells[Schema.movedIndex(i, from, to)] = current.cells[i];
            }
            freed.addAll(freedExtents(current, cells));
            next.put(current.id, new StoredRow(current.id, cells, -1, 0, true));
        }
        indexes = adapt(schema, nextSchema, next.values());
        schema = nextSchema;
        rows = next;
        pendingFree.addAll(freed);
        dirty = true;
    }

    void dropColumn(String name) {
        int index = schema.indexOf(name);
        if (index < 0) {
            throw new NexException("column not found: " + name);
        }
        if (schema.columns.size() == 1) {
            throw new NexException("the table must keep at least one column");
        }
        TreeMap<Long, StoredRow> next = new TreeMap<>();
        List<Extent> freed = new ArrayList<>();
        for (StoredRow current : rows.values()) {
            Cell[] cells = new Cell[current.cells.length - 1];
            for (int i = 0, target = 0; i < current.cells.length; i++) {
                if (i != index) {
                    cells[target++] = current.cells[i];
                }
            }
            freed.addAll(freedExtents(current, cells));
            next.put(current.id, new StoredRow(current.id, cells, -1, 0, true));
        }
        Schema nextSchema = schema.drop(index);
        validate(nextSchema);
        indexes = adapt(schema, nextSchema, next.values());
        schema = nextSchema;
        rows = next;
        pendingFree.addAll(freed);
        dirty = true;
    }

    Snapshot savepoint() {
        Snapshot snapshot = new Snapshot(schema, rows, List.copyOf(indexes), new ArrayList<>(freeExtents), new ArrayList<>(pendingFree),
                fileSize, nextRowId, metaOffset, metaLength, generation, activeSlot, dirty, shared);
        undo = snapshot;
        return snapshot;
    }

    /**
     * Copies a snapshot off the pages a checkpoint is about to overwrite.
     * The committed image keeps its rows, but its index roots and heap
     * offsets belong to the previous file layout.
     */
    Snapshot pin(Snapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        for (StoredRow row : snapshot.rows.values()) {
            for (int i = 0; i < row.cells.length; i++) {
                Cell cell = row.cells[i];
                if (!cell.onDisk()) {
                    continue;
                }
                Object value = logical(cell, snapshot.schema.columns.get(i));
                row.cells[i] = new Cell(value, -1, 0);
            }
        }
        List<IndexTree.DiskIndex> built = new ArrayList<>();
        for (IndexTree.DiskIndex index : IndexTree.empty(snapshot.schema)) {
            built.add(index.fill(snapshot.schema, snapshot.rows.values(), this::keyParts, new ArrayList<>(), pages()));
        }
        return new Snapshot(snapshot.schema, snapshot.rows, built, snapshot.freeExtents, snapshot.pendingFree,
                snapshot.fileSize, snapshot.nextRowId, snapshot.metaOffset, snapshot.metaLength,
                snapshot.generation, snapshot.activeSlot, snapshot.dirty, snapshot.shared);
    }

    void restore(Snapshot snapshot) {
        if (snapshot == undo) {
            undo = null;
        }
        schema = snapshot.schema;
        rows = snapshot.rows;
        shared = snapshot.shared;
        for (int i = snapshot.inserted.size() - 1; i >= 0; i--) {
            rows.remove(snapshot.inserted.get(i));
        }
        snapshot.inserted.clear();
        indexes = snapshot.indexes;
        freeExtents = new ArrayList<>(snapshot.freeExtents);
        pendingFree = new ArrayList<>(snapshot.pendingFree);
        fileSize = snapshot.fileSize;
        nextRowId = snapshot.nextRowId;
        metaOffset = snapshot.metaOffset;
        metaLength = snapshot.metaLength;
        generation = snapshot.generation;
        activeSlot = snapshot.activeSlot;
        dirty = snapshot.dirty;
    }

    /**
     * Separates the transaction map from the committed map. Rows that do not change
     * stay the same and commit does not rewrite them.
     */
    void markShared() {
        shared = true;
    }

    void fork() {
        rows = new TreeMap<>(rows);
        shared = false;
    }

    private void unshare() {
        if (!shared) {
            return;
        }
        rows = new TreeMap<>(rows);
        shared = false;
    }

    void raiseAllocation(Schema other, long otherNext) {
        if (otherNext > nextRowId) {
            nextRowId = otherNext;
        }
        if (other == null || other.columns.size() != schema.columns.size()) {
            return;
        }
        for (int i = 0; i < schema.columns.size(); i++) {
            Column mine = schema.columns.get(i);
            Column theirs = other.columns.get(i);
            if (mine.autoIncrement && theirs.autoIncrement && theirs.nextIdentity > mine.nextIdentity) {
                schema = schema.withIdentity(i, theirs.nextIdentity);
            }
        }
    }

    /**
     * Publishes to the committed state only the rows the transaction inserted, updated,
     * or deleted since {@code base}. Another session that changed the same row
     * blocks the publish.
     */
    void publishChanges(Snapshot base, Snapshot committed) {
        if (base == null || committed == null) {
            throw new NexException("failed internally to commit " + schema.name);
        }
        // The statement already changed this table. Commit keeps those rows, as Sybase does,
        // instead of copying every value back onto the previous image.
        if (base.rows == committed.rows && schema.sameStructure(committed.schema) && sameShape(schema, committed.schema)) {
            undo = null;
            if (rows != committed.rows || schema != committed.schema || indexes != committed.indexes
                    || fileSize != committed.fileSize || nextRowId != committed.nextRowId) {
                dirty = true;
            }
            return;
        }
        if (!schema.sameStructure(committed.schema)) {
            if (!base.schema.sameStructure(committed.schema)) {
                throw new NexException("another transaction changed the structure of " + schema.name);
            }
            if (rowsChanged(base, committed)) {
                throw new NexException("another transaction changed the same row of " + schema.name);
            }
            dirty = true;
            return;
        }
        if (!sameShape(schema, committed.schema)) {
            throw new NexException("another transaction changed the structure of " + schema.name);
        }
        String name = schema.name;
        List<Long> inserted = new ArrayList<>();
        List<Long> updated = new ArrayList<>();
        List<Long> deleted = new ArrayList<>();
        Map<Long, Object[]> insertedValues = new LinkedHashMap<>();
        Map<Long, Cell[]> updatedCells = new LinkedHashMap<>();
        for (StoredRow row : rows.values()) {
            StoredRow original = base.rows.get(row.id);
            if (original == null) {
                inserted.add(row.id);
                insertedValues.put(row.id, valuesOf(schema, row));
                continue;
            }
            if (original == row) {
                continue;
            }
            Object[] before = valuesOf(base.schema, original);
            Object[] now = valuesOf(schema, row);
            if (Arrays.deepEquals(before, now)) {
                continue;
            }
            updated.add(row.id);
            Cell[] cells = new Cell[now.length];
            for (int i = 0; i < now.length; i++) {
                cells[i] = toCell(schema.columns.get(i), now[i]);
            }
            updatedCells.put(row.id, cells);
        }
        for (Long id : base.rows.keySet()) {
            if (!rows.containsKey(id)) {
                deleted.add(id);
            }
        }
        for (Long id : updated) {
            StoredRow current = committed.rows.get(id);
            StoredRow original = base.rows.get(id);
            if (current == null || !Arrays.deepEquals(valuesOf(base.schema, original), valuesOf(committed.schema, current))) {
                throw new NexException("another transaction changed the same row of " + name);
            }
        }
        for (Long id : deleted) {
            StoredRow current = committed.rows.get(id);
            StoredRow original = base.rows.get(id);
            if (current == null || !Arrays.deepEquals(valuesOf(base.schema, original), valuesOf(committed.schema, current))) {
                throw new NexException("another transaction changed the same row of " + name);
            }
        }
        long privateNext = nextRowId;
        Schema privateSchema = schema;
        restore(committed);
        raiseAllocation(privateSchema, privateNext);
        if (inserted.isEmpty() && updated.isEmpty() && deleted.isEmpty()) {
            return;
        }
        rows = new TreeMap<>(rows);
        shared = false;
        if (!deleted.isEmpty()) {
            delete(deleted);
        }
        if (!updated.isEmpty()) {
            try {
                update(updatedCells);
            } catch (NexException exception) {
                if (exception.getMessage() != null && exception.getMessage().contains("duplicate")) {
                    throw new NexException("another transaction wrote the same key in " + name);
                }
                throw exception;
            }
        }
        if (!inserted.isEmpty()) {
            try {
                insert(new ArrayList<>(insertedValues.values()));
            } catch (NexException exception) {
                if (exception.getMessage() != null && exception.getMessage().contains("duplicate")) {
                    throw new NexException("another transaction wrote the same key in " + name);
                }
                throw exception;
            }
        }
    }

    private boolean rowsChanged(Snapshot base, Snapshot committed) {
        if (base.rows.size() != committed.rows.size()) {
            return true;
        }
        for (StoredRow row : base.rows.values()) {
            StoredRow other = committed.rows.get(row.id);
            if (other == null) {
                return true;
            }
            if (!Arrays.deepEquals(valuesOf(base.schema, row), valuesOf(committed.schema, other))) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameShape(Schema left, Schema right) {
        if (left.columns.size() != right.columns.size()) {
            return false;
        }
        for (int i = 0; i < left.columns.size(); i++) {
            Column one = left.columns.get(i);
            Column other = right.columns.get(i);
            if (!Names.same(one.name, other.name) || one.type != other.type) {
                return false;
            }
        }
        return true;
    }

    private Object[] valuesOf(Schema shape, StoredRow row) {
        Object[] values = new Object[shape.columns.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = detached(logical(row.cells[i], shape.columns.get(i)));
        }
        return values;
    }

    private static Object detached(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        return value;
    }

    Plan prepare() {
        return prepare(false);
    }

    /**
     * Rewrites live records at the start of the region. Commit uses this
     * when the current region has grown too large for what is still in the table.
     */
    Plan preparePacked() {
        return prepare(true);
    }

    long packedBytes() {
        long size = Format.HEADER_SIZE;
        for (StoredRow row : rows.values()) {
            for (int i = 0; i < row.cells.length; i++) {
                Column column = schema.columns.get(i);
                Cell cell = row.cells[i];
                if (!column.heapBacked() || cell.isNull()) {
                    continue;
                }
                long length = cell.length;
                if (cell.value instanceof byte[] bytes) {
                    length = bytes.length;
                } else if (cell.value instanceof String text) {
                    length = text.getBytes(StandardCharsets.UTF_8).length;
                }
                if (length > 0) {
                    size += Format.blobExtent(length);
                }
            }
            size += Format.slotExtent(RecordCodec.encodedSize(schema, row.cells));
        }
        return size + 1024L + rows.size() * 48L + IndexTree.footprint(indexes, pages());
    }

    void plantHeader() {
        writeAt(0, FileHeader.fresh(Format.HEADER_SIZE, 1));
    }

    private Plan prepare(boolean pack) {
        Space space = pack ? new Space(List.of(), Format.HEADER_SIZE) : new Space(freeExtents, fileSize);
        List<Write> writes = new ArrayList<>();
        TreeMap<Long, StoredRow> committed = new TreeMap<>();
        List<LiveSlot> lives = new ArrayList<>();
        for (StoredRow row : rows.values()) {
            if (!pack && !row.dirty) {
                committed.put(row.id, row);
                lives.add(new LiveSlot(row.id, row.slotOffset, row.slotSize));
                continue;
            }
            Cell[] cells = row.copyCells();
            for (int i = 0; i < cells.length; i++) {
                Column column = schema.columns.get(i);
                if (!column.heapBacked()) {
                    continue;
                }
                Cell cell = cells[i];
                if (cell.isNull() || (!pack && cell.offset >= 0) || cell.length == 0 && cell.offset >= 0) {
                    continue;
                }
                byte[] payloadBytes = heapPayload(column, cell);
                if (payloadBytes.length == 0) {
                    cells[i] = new Cell(column.type == DataType.BLOB ? new byte[0] : "", 0, 0);
                    continue;
                }
                long extent = Format.blobExtent(payloadBytes.length);
                long offset = space.allocate(extent);
                writes.add(new Write(offset, blobBlock(payloadBytes, extent)));
                Object kept = column.type == DataType.BLOB ? Cell.ON_DISK : cell.value;
                cells[i] = new Cell(kept, offset, payloadBytes.length);
            }
            byte[] payloadBytes = RecordCodec.encode(schema, cells);
            long slotSize = Format.slotExtent(payloadBytes.length);
            long slotOffset = space.allocate(slotSize);
            writes.add(new Write(slotOffset, slotBlock(row.id, payloadBytes, slotSize)));
            StoredRow stored = new StoredRow(row.id, cells, slotOffset, slotSize, false);
            committed.put(row.id, stored);
            lives.add(new LiveSlot(row.id, slotOffset, slotSize));
        }
        long[] roots = IndexTree.persist(indexes, space, writes, pages(), pack);
        List<Extent> reusable = freeForMeta(space, pack);
        byte[] natural = MetaCodec.encode(schema, nextRowId, 0, lives, reusable, roots);
        long exact = Format.align8(natural.length);
        long slack = Math.min(60000L, Math.max(256L, exact / 4));
        long wanted = Format.align8(exact + slack);
        Extent hole = smallestFit(space.remaining(), wanted);
        long reserved = hole == null ? wanted : Math.min(hole.length, wanted);
        long metaAt = space.allocate(reserved);
        reusable = freeForMeta(space, pack);
        long finalSize = space.fileSize();
        byte[] meta = MetaCodec.encode(schema, nextRowId, finalSize, lives, reusable, roots, (int) reserved);
        if (meta.length != reserved) {
            throw new NexException("failed internally to write the metadata of " + schema.name);
        }
        writes.add(new Write(metaAt, meta));
        ensureDisjoint(writes, finalSize);
        return new Plan(writes, committed, reusable, finalSize, metaAt, meta.length,
                generation + 1, activeSlot == 0 ? 1 : 0, schema, nextRowId, roots);
    }

    private List<Extent> freeForMeta(Space space, boolean pack) {
        List<Extent> reusable = new ArrayList<>(space.remaining());
        if (!pack) {
            reusable.addAll(pendingFree);
            if (metaLength > 0) {
                reusable.add(new Extent(metaOffset, Format.align8(metaLength)));
            }
            reusable = Extent.coalesce(reusable);
        }
        return reusable;
    }

    private static Extent smallestFit(List<Extent> extents, long size) {
        Extent best = null;
        for (Extent extent : extents) {
            if (extent.length >= size && (best == null || extent.length < best.length)) {
                best = extent;
            }
        }
        return best;
    }

    void armScrub() {
        byte[] header = readAt(0, Format.HEADER_SIZE);
        if (header[Format.SCRUB_OFFSET] == Format.SCRUB_DIRTY) {
            return;
        }
        header[Format.SCRUB_OFFSET] = Format.SCRUB_DIRTY;
        writeAt(0, header);
        force();
    }

    void scrub(Plan plan) {
        scrubExtents(plan.free, plan.metaOffset, plan.metaLength, plan.rows.values(), plan.schema.name);
        markScrubClean();
    }

    void scrubIfDirty() {
        byte[] header = readAt(0, Format.HEADER_SIZE);
        if (header[Format.SCRUB_OFFSET] == Format.SCRUB_CLEAN) {
            return;
        }
        scrubExtents(freeExtents, metaOffset, metaLength, rows.values(), schema.name);
        if (fileSize < capacity) {
            file.zero(base + fileSize, capacity - fileSize);
            force();
        }
        markScrubClean();
    }

    void writePayload(Plan plan) {
        for (Write write : plan.writes) {
            writeAt(write.offset, write.bytes);
        }
        force();
    }

    void flip(Plan plan) {
        byte[] header = readAt(0, Format.HEADER_SIZE);
        FileHeader.parse(header);
        header[Format.SCRUB_OFFSET] = Format.SCRUB_DIRTY;
        ByteBuffer.wrap(header).order(Format.ORDER).putShort(Format.VERSION_OFFSET, (short) Format.VERSION);
        FileHeader.writeSlot(header, plan.targetSlot, plan.generation, plan.metaOffset, plan.metaLength);
        writeAt(0, header);
        force();
    }

    void install(Plan plan) {
        schema = plan.schema;
        rows = plan.rows;
        freeExtents = new ArrayList<>(plan.free);
        pendingFree = new ArrayList<>();
        fileSize = plan.fileSize;
        metaOffset = plan.metaOffset;
        metaLength = plan.metaLength;
        generation = plan.generation;
        activeSlot = plan.targetSlot;
        nextRowId = plan.nextRowId;
        dirty = false;
        shared = false;
        indexes = IndexTree.attach(plan.schema, plan.indexRoots);
    }

    void truncateToCommitted() {
    }

    void reload() {
        byte[] headerBytes = readAt(0, Format.HEADER_SIZE);
        boolean scrub = headerBytes[Format.SCRUB_OFFSET] != Format.SCRUB_CLEAN;
        FileHeader header = FileHeader.parse(headerBytes);
        FileHeader.Slot active = header.active();
        byte[] encoded = readAt(active.metaOffset(), active.metaLength());
        MetaImage image = MetaCodec.decode(encoded);
        schema = image.schema;
        fileSize = image.fileSize;
        if (fileSize > capacity) {
            throw new NexException("region of table " + schema.name + " cannot hold the data");
        }
        metaOffset = active.metaOffset();
        metaLength = active.metaLength();
        generation = active.generation();
        activeSlot = header.activeIndex();
        nextRowId = image.nextRowId;
        freeExtents = new ArrayList<>();
        for (Extent extent : image.free) {
            if (!overlaps(extent.offset, extent.length, metaOffset, metaLength) && extent.offset + extent.length <= fileSize) {
                freeExtents.add(extent);
            }
        }
        freeExtents = Extent.coalesce(freeExtents);
        pendingFree = new ArrayList<>();
        rows = new TreeMap<>();
        shared = false;
        boolean droppedConnection = false;
        for (LiveSlot slot : image.rows) {
            try {
                StoredRow row = readSlot(slot);
                rows.put(row.id, row);
            } catch (NexException exception) {
                if (!Names.same(schema.name, SystemTables.CONNECTIONS) || !exception.getMessage().startsWith("corrupt record")) {
                    throw exception;
                }
                droppedConnection = true;
                if (!blocked(slot.offset, slot.size)) {
                    freeExtents.add(new Extent(slot.offset, slot.size));
                }
            }
        }
        if (droppedConnection) {
            freeExtents = Extent.coalesce(freeExtents);
            dirty = true;
        } else {
            dirty = false;
        }
        indexes = IndexTree.attach(schema, image.indexRoots);
        if (scrub) {
            scrubExtents(freeExtents, metaOffset, metaLength, rows.values(), schema.name);
            if (fileSize < capacity) {
                file.zero(base + fileSize, capacity - fileSize);
                force();
            }
            markScrubClean();
        }
    }

    @Override
    public void close() {
    }

    private void initNew(Schema schema) {
        this.schema = schema;
        indexes = IndexTree.empty(schema);
        long[] roots = new long[indexes.size()];
        byte[] draft = MetaCodec.encode(schema, 1, 0, List.of(), List.of(), roots);
        long size = Format.HEADER_SIZE + draft.length;
        byte[] meta = MetaCodec.encode(schema, 1, size, List.of(), List.of(), roots);
        if (meta.length != draft.length) {
            throw new NexException("failed internally to create table " + schema.name);
        }
        writeAt(0, FileHeader.fresh(Format.HEADER_SIZE, meta.length));
        writeAt(Format.HEADER_SIZE, meta);
        force();
        fileSize = size;
        metaOffset = Format.HEADER_SIZE;
        metaLength = meta.length;
        generation = 1;
        activeSlot = 0;
        nextRowId = 1;
        dirty = false;
    }

    private StoredRow readSlot(LiveSlot slot) {
        if (slot.size <= Format.BLOCK_HEADER || slot.size > Integer.MAX_VALUE) {
            throw new NexException("corrupt record in table " + schema.name);
        }
        byte[] block = readAt(slot.offset, (int) slot.size);
        ByteBuffer buffer = ByteBuffer.wrap(block).order(Format.ORDER);
        int magic = buffer.getInt();
        int declared = buffer.getInt();
        long blockSize = buffer.getLong();
        long rowId = buffer.getLong();
        if (magic != Format.SLOT_MAGIC || blockSize != slot.size || rowId != slot.rowId) {
            throw new NexException("corrupt record in table " + schema.name);
        }
        if (declared <= 0 || Format.slotExtent(declared) != slot.size || buffer.remaining() < declared) {
            throw new NexException("corrupt record in table " + schema.name);
        }
        byte[] payload = new byte[declared];
        buffer.get(payload);
        Cell[] cells = RecordCodec.decode(schema, payload);
        return new StoredRow(rowId, cells, slot.offset, slot.size, false);
    }

    private byte[] readBlob(long offset, long length) {
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new NexException("corrupt BLOB in table " + schema.name);
        }
        long extent = Format.blobExtent(length);
        byte[] block = readAt(offset, (int) extent);
        ByteBuffer buffer = ByteBuffer.wrap(block).order(Format.ORDER);
        int magic = buffer.getInt();
        buffer.getInt();
        long blockSize = buffer.getLong();
        long payloadLength = buffer.getLong();
        if (magic != Format.BLOB_MAGIC || blockSize != extent || payloadLength != length) {
            throw new NexException("corrupt BLOB in table " + schema.name);
        }
        byte[] data = new byte[(int) length];
        buffer.get(data);
        return data;
    }

    private Object logical(Cell cell, Column column) {
        if (cell.isNull()) {
            return null;
        }
        if (!cell.onDisk()) {
            return cell.value;
        }
        byte[] data = readBlob(cell.offset, cell.length);
        if (column.character()) {
            return new String(data, StandardCharsets.UTF_8);
        }
        return data;
    }

    private void indexInserted(StoredRow row) {
        List<IndexTree.DiskIndex> next = new ArrayList<>(indexes.size());
        List<Extent> extra = new ArrayList<>();
        for (IndexTree.DiskIndex index : indexes) {
            Object[] values = keyParts(schema, index, row);
            next.add(values == null ? index : index.insert(schema, values, row.id, extra, pages()));
        }
        indexes = next;
        pendingFree.addAll(extra);
    }

    private void unindex(StoredRow row, List<Extent> extra) {
        List<IndexTree.DiskIndex> next = new ArrayList<>(indexes.size());
        for (IndexTree.DiskIndex index : indexes) {
            Object[] values = keyParts(schema, index, row);
            next.add(values == null ? index : index.delete(schema, values, row.id, extra, pages()));
        }
        indexes = next;
    }

    private void reindex(Schema before, StoredRow current, Schema after, StoredRow replacement, List<Extent> extra) {
        List<IndexTree.DiskIndex> next = new ArrayList<>(indexes.size());
        for (IndexTree.DiskIndex index : indexes) {
            Object[] oldValues = keyParts(before, index, current);
            Object[] newValues = keyParts(after, index, replacement);
            if (java.util.Arrays.deepEquals(oldValues, newValues)) {
                next.add(index);
                continue;
            }
            IndexTree.DiskIndex changed = index;
            if (oldValues != null) {
                changed = changed.delete(before, oldValues, current.id, extra, pages());
            }
            if (newValues != null) {
                changed = changed.insert(after, newValues, current.id, extra, pages());
            }
            next.add(changed);
        }
        indexes = next;
    }

    private List<IndexTree.DiskIndex> indexesAfterAdd(Schema nextSchema, Map<Long, StoredRow> rows, boolean primary) {
        List<IndexTree.DiskIndex> next = new ArrayList<>();
        List<Extent> extra = new ArrayList<>();
        int column = nextSchema.columns.size() - 1;
        if (schema.primaryKey.length > 0) {
            next.add(indexes.get(0));
        } else if (primary) {
            next.add(IndexTree.DiskIndex.empty(new IndexTree.Spec(nextSchema.primaryIndex, new int[]{column}, true, true))
                    .fill(nextSchema, rows.values(), this::keyParts, extra, pages()));
        }
        int uniqueFrom = schema.primaryKey.length > 0 ? 1 : 0;
        int uniqueCount = schema.uniques.size();
        for (int i = uniqueFrom; i < uniqueFrom + uniqueCount; i++) {
            next.add(indexes.get(i));
        }
        if (nextSchema.uniques.size() > schema.uniques.size()) {
            UniqueKey added = nextSchema.uniques.get(nextSchema.uniques.size() - 1);
            next.add(IndexTree.DiskIndex.empty(new IndexTree.Spec(added.name, added.columns, false, true))
                    .fill(nextSchema, rows.values(), this::keyParts, extra, pages()));
        }
        for (int i = uniqueFrom + uniqueCount; i < indexes.size(); i++) {
            next.add(indexes.get(i));
        }
        pendingFree.addAll(extra);
        return next;
    }

    private List<IndexTree.DiskIndex> adapt(Schema before, Schema after, java.util.Collection<StoredRow> rows) {
        List<IndexTree.Spec> wanted = IndexTree.specs(after);
        boolean[] used = new boolean[indexes.size()];
        List<Extent> extra = new ArrayList<>();
        List<IndexTree.DiskIndex> next = new ArrayList<>();
        for (IndexTree.Spec spec : wanted) {
            int found = match(before, after, spec, used);
            if (found >= 0) {
                used[found] = true;
                next.add(indexes.get(found).withColumns(spec.columns()));
            } else {
                next.add(IndexTree.DiskIndex.empty(spec).fill(after, rows, this::keyParts, extra, pages()));
            }
        }
        for (int i = 0; i < indexes.size(); i++) {
            if (!used[i]) {
                indexes.get(i).release(extra, pages());
            }
        }
        pendingFree.addAll(extra);
        return next;
    }

    private int match(Schema before, Schema after, IndexTree.Spec spec, boolean[] used) {
        for (int i = 0; i < indexes.size(); i++) {
            if (used[i]) {
                continue;
            }
            IndexTree.DiskIndex index = indexes.get(i);
            if (index.primary != spec.primary() || index.unique != spec.unique()) {
                continue;
            }
            if (index.columns.length != spec.columns().length) {
                continue;
            }
            boolean same = true;
            for (int column = 0; column < spec.columns().length; column++) {
                String previous = before.columns.get(index.columns[column]).name;
                String current = after.columns.get(spec.columns()[column]).name;
                if (!Names.same(previous, current)) {
                    same = false;
                    break;
                }
            }
            if (!same) {
                continue;
            }
            if (!spec.primary() && !generatedName(spec.name()) && !Names.same(index.name, spec.name())) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static boolean generatedName(String name) {
        return Names.generatedUnique(name);
    }

    private Object[] keyParts(Schema target, IndexTree.DiskIndex index, StoredRow row) {
        Object[] values = new Object[index.columns.length];
        for (int i = 0; i < index.columns.length; i++) {
            int columnIndex = index.columns[i];
            Column column = target.columns.get(columnIndex);
            Object value = logical(row.cells[columnIndex], column);
            if (value == null) {
                if (index.primary) {
                    throw new NexException("primary key does not accept NULL in " + target.name + "." + column.name);
                }
                return null;
            }
            values[i] = value;
        }
        return values;
    }

    private IndexTree.PageSource pages() {
        return this::readAt;
    }

    static Cell toCell(Column column, Object value) {
        if (value == null) {
            return Cell.sqlNull();
        }
        if (!column.heapBacked()) {
            return new Cell(Values.coerce(column.type, column.length, column.scale, value), -1, 0);
        }
        if (column.type == DataType.BLOB) {
            byte[] bytes = (byte[]) value;
            return bytes.length == 0 ? new Cell(bytes, 0, 0) : new Cell(bytes, -1, bytes.length);
        }
        String text = (String) value;
        if (text.isEmpty()) {
            return new Cell(text, 0, 0);
        }
        return new Cell(text, -1, text.getBytes(StandardCharsets.UTF_8).length);
    }

    private static List<Extent> freedExtents(StoredRow current, Cell[] kept) {
        List<Extent> extents = new ArrayList<>();
        if (current.slotOffset >= 0 && current.slotSize > 0) {
            extents.add(new Extent(current.slotOffset, current.slotSize));
        }
        Set<Long> preserve = new HashSet<>();
        if (kept != null) {
            for (Cell cell : kept) {
                if (cell != null && cell.offset > 0) {
                    preserve.add(cell.offset);
                }
            }
        }
        for (Cell cell : current.cells) {
            if (cell.offset > 0 && !preserve.contains(cell.offset)) {
                extents.add(new Extent(cell.offset, Format.blobExtent(cell.length)));
            }
        }
        return extents;
    }

    private byte[] heapPayload(Column column, Cell cell) {
        if (cell.value instanceof byte[] bytes) {
            return bytes;
        }
        if (cell.value instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        if (cell.offset > 0) {
            return readBlob(cell.offset, cell.length);
        }
        throw new NexException("invalid value in column " + column.name);
    }

    private static byte[] blobBlock(byte[] payload, long extent) {
        ByteBuffer buffer = ByteBuffer.allocate((int) extent).order(Format.ORDER);
        buffer.putInt(Format.BLOB_MAGIC);
        buffer.putInt(0);
        buffer.putLong(extent);
        buffer.putLong(payload.length);
        buffer.put(payload);
        return buffer.array();
    }

    private static byte[] slotBlock(long rowId, byte[] payload, long extent) {
        ByteBuffer buffer = ByteBuffer.allocate((int) extent).order(Format.ORDER);
        buffer.putInt(Format.SLOT_MAGIC);
        buffer.putInt(payload.length);
        buffer.putLong(extent);
        buffer.putLong(rowId);
        buffer.put(payload);
        return buffer.array();
    }

    private static void ensureDisjoint(List<Write> writes, long finalSize) {
        List<Write> ordered = new ArrayList<>(writes);
        ordered.sort((left, right) -> Long.compare(left.offset, right.offset));
        long end = Format.HEADER_SIZE;
        for (Write write : ordered) {
            if (write.offset < end) {
                throw new NexException("internal failure: overlapping writes");
            }
            end = write.offset + write.bytes.length;
            if (end > finalSize) {
                throw new NexException("internal failure: write past the end of the file");
            }
        }
    }

    private void scrubExtents(List<Extent> extents, long metaAt, int metaBytes, Collection<StoredRow> live, String name) {
        long metaSpan = Format.align8(metaBytes);
        List<Extent> victims = Extent.coalesce(extents);
        for (Extent extent : victims) {
            if (extent.length <= 0) {
                continue;
            }
            if (extent.offset < 0 || extent.offset + extent.length > capacity) {
                throw new NexException("free space is outside table " + name);
            }
            if (overlaps(extent.offset, extent.length, metaAt, metaSpan)) {
                throw new NexException("free space overlaps the metadata of " + name);
            }
            for (StoredRow row : live) {
                if (row.slotOffset >= 0 && overlaps(extent.offset, extent.length, row.slotOffset, row.slotSize)) {
                    throw new NexException("free space overlaps a record of " + name);
                }
            }
        }
        boolean wrote = false;
        for (Extent extent : victims) {
            if (extent.length <= 0) {
                continue;
            }
            file.zero(base + extent.offset, extent.length);
            wrote = true;
        }
        if (wrote) {
            force();
        }
    }

    private void markScrubClean() {
        byte[] header = readAt(0, Format.HEADER_SIZE);
        if (header[Format.SCRUB_OFFSET] == Format.SCRUB_CLEAN) {
            return;
        }
        header[Format.SCRUB_OFFSET] = Format.SCRUB_CLEAN;
        writeAt(0, header);
        force();
    }

    /** A dropped connection slot must not be handed out over a live row, the metadata, or free space. */
    private boolean blocked(long offset, long size) {
        if (offset < Format.HEADER_SIZE || size <= Format.BLOCK_HEADER || offset + size < offset || offset + size > fileSize) {
            return true;
        }
        if (overlaps(offset, size, metaOffset, Format.align8(metaLength))) {
            return true;
        }
        for (Extent extent : freeExtents) {
            if (overlaps(offset, size, extent.offset, extent.length)) {
                return true;
            }
        }
        for (StoredRow row : rows.values()) {
            if (row.slotOffset >= 0 && overlaps(offset, size, row.slotOffset, row.slotSize)) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlaps(long left, long leftLength, long right, long rightLength) {
        return left < right + rightLength && right < left + leftLength;
    }

    private void writeAt(long logical, byte[] data) {
        if (logical < 0 || data.length < 0 || logical + data.length > capacity) {
            throw new NexException("write outside the table area " + (schema == null ? "" : schema.name));
        }
        file.writeBytes(base + logical, data);
    }

    private byte[] readAt(long logical, int length) {
        if (logical < 0 || length < 0 || logical + length > capacity) {
            throw new NexException("read outside the table area " + (schema == null ? "" : schema.name));
        }
        return file.readBytes(base + logical, length);
    }

    private void force() {
        file.force();
    }

    private static void validate(Schema schema) {
        if (schema.columns.isEmpty()) {
            throw new NexException("the table requires at least one column");
        }
        if (schema.columns.size() > Format.MAX_COLUMNS) {
            throw new NexException("the table exceeds " + Format.MAX_COLUMNS + " columns");
        }
        if (RecordCodec.maxPayloadSize(schema) > Format.MAX_PAYLOAD) {
            throw new NexException("record is too large; use BLOB for long fields");
        }
        Set<String> names = new HashSet<>();
        for (Column column : schema.columns) {
            if (!names.add(Names.fold(column.name))) {
                throw new NexException("duplicate column: " + column.name);
            }
            if (column.autoIncrement && !column.type.exactInteger()) {
                throw new NexException("AUTOINCREMENT is only allowed on SMALLINT, INTEGER, or BIGINT");
            }
        }
        rejectBlobKey(schema, schema.primaryKey);
        for (UniqueKey unique : schema.uniques) {
            rejectBlobKey(schema, unique.columns);
        }
        for (IndexDef index : schema.indexes) {
            if (index.columns.length == 0) {
                throw new NexException("index requires at least one column");
            }
            rejectBlobKey(schema, index.columns);
        }
    }

    private static void rejectBlobKey(Schema schema, int[] indexes) {
        for (int index : indexes) {
            if (schema.columns.get(index).type == DataType.BLOB) {
                throw new NexException("BLOB cannot be a key");
            }
        }
    }

    static final class Snapshot {
        final Schema schema;
        final TreeMap<Long, StoredRow> rows;
        final List<IndexTree.DiskIndex> indexes;
        final List<Extent> freeExtents;
        final List<Extent> pendingFree;
        final long fileSize;
        final long nextRowId;
        final long metaOffset;
        final int metaLength;
        final long generation;
        final int activeSlot;
        final boolean dirty;
        final boolean shared;
        final List<Long> inserted = new ArrayList<>();

        Snapshot(Schema schema, TreeMap<Long, StoredRow> rows, List<IndexTree.DiskIndex> indexes, List<Extent> freeExtents,
                 List<Extent> pendingFree, long fileSize, long nextRowId, long metaOffset, int metaLength, long generation,
                 int activeSlot, boolean dirty, boolean shared) {
            this.schema = schema;
            this.rows = rows;
            this.indexes = indexes;
            this.freeExtents = freeExtents;
            this.pendingFree = pendingFree;
            this.fileSize = fileSize;
            this.nextRowId = nextRowId;
            this.metaOffset = metaOffset;
            this.metaLength = metaLength;
            this.generation = generation;
            this.activeSlot = activeSlot;
            this.dirty = dirty;
            this.shared = shared;
        }
    }

    static final class Write {
        final long offset;
        final byte[] bytes;

        Write(long offset, byte[] bytes) {
            this.offset = offset;
            this.bytes = bytes;
        }
    }

    static final class Plan {
        final List<Write> writes;
        final TreeMap<Long, StoredRow> rows;
        final List<Extent> free;
        final long fileSize;
        final long metaOffset;
        final int metaLength;
        final long generation;
        final int targetSlot;
        final Schema schema;
        final long nextRowId;
        final long[] indexRoots;

        Plan(List<Write> writes, TreeMap<Long, StoredRow> rows, List<Extent> free, long fileSize, long metaOffset,
             int metaLength, long generation, int targetSlot, Schema schema, long nextRowId, long[] indexRoots) {
            this.writes = writes;
            this.rows = rows;
            this.free = free;
            this.fileSize = fileSize;
            this.metaOffset = metaOffset;
            this.metaLength = metaLength;
            this.generation = generation;
            this.targetSlot = targetSlot;
            this.schema = schema;
            this.nextRowId = nextRowId;
            this.indexRoots = indexRoots;
        }
    }
}
