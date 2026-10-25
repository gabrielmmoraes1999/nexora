package io.github.gabrielmmoraes1999.nexora;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Rows of one table. A checkpoint freezes this map in constant time and
 * sends later inserts to a small layer on top, so the insert does not copy
 * every row it already stored.
 */
final class RowMap {
    private RowMap parent;
    private TreeMap<Long, StoredRow> local = new TreeMap<>();
    private int size;
    private int dirtyCount;
    private boolean frozen;

    /** Stops mutation and returns the layer that receives later inserts. */
    RowMap sprout() {
        frozen = true;
        RowMap child = new RowMap();
        child.parent = this;
        child.size = size;
        child.dirtyCount = dirtyCount;
        return child;
    }

    /**
     * Points this layer at a clean image of the rows frozen for the checkpoint.
     * Inserts that arrived after the freeze stay in {@code local}.
     */
    boolean reparentClean(RowMap base) {
        int extra = 0;
        int dirty = 0;
        for (StoredRow row : local.values()) {
            if (row == null) {
                return false;
            }
            extra++;
            if (row.dirty) {
                dirty++;
            }
        }
        parent = base;
        size = base.size + extra;
        dirtyCount = dirty;
        return true;
    }

    StoredRow get(long id) {
        if (local.containsKey(id)) {
            return local.get(id);
        }
        return parent == null ? null : parent.get(id);
    }

    boolean containsKey(long id) {
        return get(id) != null;
    }

    void put(long id, StoredRow row) {
        if (frozen) {
            throw new NexException("internal failure: frozen row map");
        }
        StoredRow previous = get(id);
        local.put(id, row);
        if (previous == null) {
            size++;
        }
        if (row.dirty && (previous == null || !previous.dirty)) {
            dirtyCount++;
        } else if (!row.dirty && previous != null && previous.dirty) {
            dirtyCount--;
        }
    }

    void remove(long id) {
        if (frozen) {
            throw new NexException("internal failure: frozen row map");
        }
        StoredRow previous = get(id);
        if (previous == null) {
            return;
        }
        local.put(id, null);
        size--;
        if (previous.dirty) {
            dirtyCount--;
        }
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    int dirtyCount() {
        return dirtyCount;
    }

    void forEach(Consumer<StoredRow> action) {
        if (parent == null) {
            for (StoredRow row : local.values()) {
                if (row != null) {
                    action.accept(row);
                }
            }
            return;
        }
        if (local.isEmpty()) {
            parent.forEach(action);
            return;
        }
        Set<Long> covered = new HashSet<>(local.keySet());
        parent.forEach(row -> {
            if (!covered.contains(row.id)) {
                action.accept(row);
            }
        });
        for (StoredRow row : local.values()) {
            if (row != null) {
                action.accept(row);
            }
        }
    }

    Collection<StoredRow> values() {
        return tree().values();
    }

    Set<Long> keySet() {
        return tree().keySet();
    }

    /** Sorted copy of the visible rows. */
    TreeMap<Long, StoredRow> tree() {
        TreeMap<Long, StoredRow> merged = new TreeMap<>();
        collect(merged);
        return merged;
    }

    RowMap copy() {
        return adopt(tree());
    }

    static RowMap adoptClean(TreeMap<Long, StoredRow> tree) {
        RowMap map = new RowMap();
        map.local = tree;
        map.size = tree.size();
        return map;
    }

    static RowMap adopt(TreeMap<Long, StoredRow> tree) {
        RowMap map = new RowMap();
        map.local = tree;
        map.size = tree.size();
        int dirty = 0;
        for (StoredRow row : tree.values()) {
            if (row.dirty) {
                dirty++;
            }
        }
        map.dirtyCount = dirty;
        return map;
    }

    private void collect(TreeMap<Long, StoredRow> into) {
        if (parent != null) {
            parent.collect(into);
        }
        for (var entry : local.entrySet()) {
            if (entry.getValue() == null) {
                into.remove(entry.getKey());
            } else {
                into.put(entry.getKey(), entry.getValue());
            }
        }
    }
}
