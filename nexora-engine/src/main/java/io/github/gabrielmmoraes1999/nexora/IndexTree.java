package io.github.gabrielmmoraes1999.nexora;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Índice em páginas de {@link Format#INDEX_PAGE} bytes dentro da região da tabela.
 * A busca desce da raiz até a folha e lê só essas páginas. O que ainda não foi
 * gravado fica na memória; o commit escreve páginas novas e só então publica a raiz.
 */
final class IndexTree {

    interface PageSource {
        byte[] read(long offset, int length);
    }

    record Spec(String name, int[] columns, boolean primary, boolean unique) {
    }

    static List<Spec> specs(Schema schema) {
        List<Spec> list = new ArrayList<>();
        if (schema.primaryKey.length > 0) {
            list.add(new Spec(schema.primaryIndex, schema.primaryKey, true, true));
        }
        int sequence = 1;
        for (int[] unique : schema.uniques) {
            list.add(new Spec("UNIQUE_" + sequence++, unique, false, true));
        }
        for (IndexDef index : schema.indexes) {
            list.add(new Spec(index.name, index.columns, false, index.unique));
        }
        return list;
    }

    static List<DiskIndex> empty(Schema schema) {
        List<DiskIndex> indexes = new ArrayList<>();
        for (Spec spec : specs(schema)) {
            indexes.add(DiskIndex.empty(spec));
        }
        return indexes;
    }

    static List<DiskIndex> attach(Schema schema, long[] roots) {
        List<Spec> defined = specs(schema);
        if (roots.length != defined.size()) {
            throw new NexException("índice corrompido na tabela " + schema.name);
        }
        List<DiskIndex> indexes = new ArrayList<>();
        for (int i = 0; i < defined.size(); i++) {
            indexes.add(DiskIndex.stored(defined.get(i), roots[i]));
        }
        return indexes;
    }

    static long[] persist(List<DiskIndex> indexes, Space space, List<TableStore.Write> writes, PageSource pages, boolean pack) {
        long[] roots = new long[indexes.size()];
        for (int i = 0; i < indexes.size(); i++) {
            roots[i] = indexes.get(i).persist(space, writes, pages, pack);
        }
        return roots;
    }

    static long footprint(List<DiskIndex> indexes, PageSource pages) {
        long total = 0;
        for (DiskIndex index : indexes) {
            total += index.footprint(pages);
        }
        return total;
    }

    private IndexTree() {
    }

    static final class DiskIndex {
        final String name;
        final int[] columns;
        final boolean primary;
        final boolean unique;
        final Node root;
        final long rootOffset;

        private DiskIndex(String name, int[] columns, boolean primary, boolean unique, Node root, long rootOffset) {
            this.name = name;
            this.columns = columns.clone();
            this.primary = primary;
            this.unique = unique || primary;
            this.root = root;
            this.rootOffset = rootOffset;
        }

        static DiskIndex empty(Spec spec) {
            return new DiskIndex(spec.name, spec.columns, spec.primary, spec.unique, null, 0);
        }

        static DiskIndex stored(Spec spec, long rootOffset) {
            return new DiskIndex(spec.name, spec.columns, spec.primary, spec.unique, null, rootOffset);
        }

        DiskIndex withColumns(int[] mapped) {
            return new DiskIndex(name, mapped, primary, unique, root, rootOffset);
        }

        boolean covers(Map<Integer, Object> equals) {
            if (columns.length == 0) {
                return false;
            }
            for (int column : columns) {
                if (!equals.containsKey(column)) {
                    return false;
                }
            }
            return true;
        }

        List<Long> find(Schema schema, Object[] values, PageSource pages) {
            byte[] key = IndexKeys.encode(schema, columns, values);
            Node leaf = descend(live(pages), key, schema, pages);
            if (leaf == null) {
                return List.of();
            }
            int at = leaf.exact(schema, columns, key);
            if (at < 0) {
                return List.of();
            }
            long[] ids = leaf.ids[at];
            List<Long> found = new ArrayList<>(ids.length);
            for (long id : ids) {
                found.add(id);
            }
            return found;
        }

        DiskIndex insert(Schema schema, Object[] values, long rowId, List<Extent> freed, PageSource pages) {
            byte[] key = IndexKeys.encode(schema, columns, values);
            Split split = insertRec(live(pages), key, values, rowId, schema, freed, pages);
            if (split.left == null) {
                return this;
            }
            Node next = split.right == null ? split.left : branch(split.separator, split.left, split.right);
            return new DiskIndex(name, columns, primary, unique, next, 0);
        }

        DiskIndex delete(Schema schema, Object[] values, long rowId, List<Extent> freed, PageSource pages) {
            byte[] key = IndexKeys.encode(schema, columns, values);
            Node live = live(pages);
            if (live == null) {
                throw corrupted(schema);
            }
            Node next = deleteRec(live, key, rowId, schema, freed, pages);
            next = collapse(next, pages);
            if (next != null && next.leaf && next.keys.length == 0) {
                next = null;
            }
            return new DiskIndex(name, columns, primary, unique, next, 0);
        }

        DiskIndex fill(Schema schema, Iterable<StoredRow> rows, KeyReader reader, List<Extent> freed, PageSource pages) {
            DiskIndex current = this;
            for (StoredRow row : rows) {
                Object[] values = reader.parts(schema, current, row);
                if (values == null) {
                    continue;
                }
                current = current.insert(schema, values, row.id, freed, pages);
            }
            return current;
        }

        void release(List<Extent> freed, PageSource pages) {
            if (root == null && rootOffset == 0) {
                return;
            }
            releaseNode(root != null ? root : Pages.read(pages, rootOffset), freed, pages, new HashSet<>());
        }

        long persist(Space space, List<TableStore.Write> writes, PageSource pages, boolean pack) {
            Node live = root;
            if (live == null && rootOffset > 0) {
                if (!pack) {
                    return rootOffset;
                }
                live = Pages.read(pages, rootOffset);
            }
            if (live == null) {
                return 0;
            }
            return place(live, space, writes, pages, pack);
        }

        long footprint(PageSource pages) {
            if (root == null && rootOffset == 0) {
                return 0;
            }
            try {
                return measure(root != null ? root : Pages.read(pages, rootOffset), pages, new HashSet<>());
            } catch (NexException exception) {
                return Format.INDEX_PAGE;
            }
        }

        private Node live(PageSource pages) {
            if (root != null) {
                return root;
            }
            if (rootOffset == 0) {
                return null;
            }
            return Pages.read(pages, rootOffset);
        }

        private long place(Node node, Space space, List<TableStore.Write> writes, PageSource pages, boolean pack) {
            if (!pack && !node.dirty && node.diskOffset > 0) {
                return node.diskOffset;
            }
            long[] placed = null;
            if (!node.leaf) {
                placed = new long[node.childOffsets.length];
                for (int i = 0; i < placed.length; i++) {
                    Node child = node.children == null ? null : node.children[i];
                    if (child == null) {
                        long offset = node.childOffsets[i];
                        if (offset <= 0) {
                            throw new NexException("índice corrompido");
                        }
                        placed[i] = pack ? place(Pages.read(pages, offset), space, writes, pages, true) : offset;
                    } else {
                        placed[i] = place(child, space, writes, pages, pack);
                    }
                }
            }
            byte[] page = Pages.encode(node, placed, space, writes);
            long offset = space.allocate(page.length);
            writes.add(new TableStore.Write(offset, page));
            return offset;
        }

        private static long measure(Node node, PageSource pages, Set<Long> seen) {
            long total = Format.INDEX_PAGE;
            total += overflowBytes(node);
            if (node.leaf) {
                return total;
            }
            for (int i = 0; i < node.childOffsets.length; i++) {
                Node child = node.children == null ? null : node.children[i];
                if (child != null) {
                    total += measure(child, pages, seen);
                    continue;
                }
                long offset = node.childOffsets[i];
                if (offset > 0 && seen.add(offset)) {
                    total += measure(Pages.read(pages, offset), pages, seen);
                }
            }
            return total;
        }

        private static long overflowBytes(Node node) {
            long total = 0;
            for (int i = 0; i < node.keys.length; i++) {
                int stored = node.keyOverflowLength != null && i < node.keyOverflowLength.length
                        ? node.keyOverflowLength[i] : 0;
                int length = stored > 0 ? stored : node.keys[i].length > 700 ? node.keys[i].length : 0;
                if (length > 0) {
                    total += Format.align8(8L + length);
                }
                if (node.leaf && node.ids != null && i < node.ids.length && node.ids[i] != null && node.ids[i].length > 32) {
                    int idsLength = node.idsOverflowLength != null && i < node.idsOverflowLength.length
                            && node.idsOverflowLength[i] > 0
                            ? node.idsOverflowLength[i] : node.ids[i].length * 8;
                    total += Format.align8(8L + idsLength);
                }
            }
            return total;
        }

        private void releaseNode(Node node, List<Extent> freed, PageSource pages, Set<Long> seen) {
            if (node.diskOffset > 0 && !seen.add(node.diskOffset)) {
                return;
            }
            retire(node, freed);
            if (node.leaf) {
                return;
            }
            for (int i = 0; i < node.childOffsets.length; i++) {
                Node child = node.children == null ? null : node.children[i];
                if (child != null) {
                    releaseNode(child, freed, pages, seen);
                } else if (node.childOffsets[i] > 0) {
                    releaseNode(Pages.read(pages, node.childOffsets[i]), freed, pages, seen);
                }
            }
        }

        private Split insertRec(Node node, byte[] key, Object[] values, long rowId, Schema schema,
                                List<Extent> freed, PageSource pages) {
            if (node == null) {
                return new Split(Node.leaf(key, new long[]{rowId}), null, null);
            }
            if (node.leaf) {
                int at = node.exact(schema, columns, key);
                if (at >= 0 && contains(node.ids[at], rowId)) {
                    return new Split(null, null, null);
                }
                if (at >= 0 && unique) {
                    throw duplicate(schema, values);
                }
                Node current = cow(node, freed);
                current.put(schema, columns, key, rowId);
                if (Pages.fits(current)) {
                    return new Split(current, null, null);
                }
                return current.splitLeaf();
            }
            int at = node.childIndex(schema, columns, key);
            Split below = insertRec(load(node, at, pages), key, values, rowId, schema, freed, pages);
            if (below.left == null) {
                return below;
            }
            Node current = cow(node, freed);
            current.children[at] = below.left;
            current.childOffsets[at] = 0;
            if (below.right == null) {
                return new Split(current, null, null);
            }
            current.insertChild(at, below.separator, below.right);
            if (Pages.fits(current)) {
                return new Split(current, null, null);
            }
            return current.splitInternal();
        }

        private Node deleteRec(Node node, byte[] key, long rowId, Schema schema, List<Extent> freed, PageSource pages) {
            Node current = cow(node, freed);
            if (current.leaf) {
                if (!current.remove(schema, columns, key, rowId)) {
                    throw corrupted(schema);
                }
                return current;
            }
            int at = current.childIndex(schema, columns, key);
            Node child = deleteRec(load(current, at, pages), key, rowId, schema, freed, pages);
            child = collapse(child, pages);
            if (child == null || (child.leaf && child.keys.length == 0)) {
                return current.removeChild(at);
            }
            current.children[at] = child;
            current.childOffsets[at] = 0;
            return current;
        }

        private static Node collapse(Node node, PageSource pages) {
            if (node == null || node.leaf || node.keys.length > 0) {
                return node;
            }
            if (node.childOffsets.length != 1) {
                return node;
            }
            Node only = node.children == null ? null : node.children[0];
            if (only != null) {
                return only;
            }
            if (node.childOffsets[0] <= 0) {
                return null;
            }
            return Pages.read(pages, node.childOffsets[0]);
        }

        private Node descend(Node node, byte[] key, Schema schema, PageSource pages) {
            Node current = node;
            while (current != null && !current.leaf) {
                current = load(current, current.childIndex(schema, columns, key), pages);
            }
            return current;
        }

        private static Node load(Node parent, int index, PageSource pages) {
            if (parent.children != null && parent.children[index] != null) {
                return parent.children[index];
            }
            long offset = parent.childOffsets[index];
            if (offset <= 0) {
                throw new NexException("índice corrompido");
            }
            return Pages.read(pages, offset);
        }

        private static Node cow(Node node, List<Extent> freed) {
            if (node.diskOffset > 0) {
                retire(node, freed);
            }
            return node.copy();
        }

        private static void retire(Node node, List<Extent> freed) {
            if (node.diskOffset > 0) {
                freed.add(new Extent(node.diskOffset, Format.INDEX_PAGE));
            }
            if (node.keyOverflowOffset != null) {
                for (int i = 0; i < node.keyOverflowOffset.length; i++) {
                    if (node.keyOverflowOffset[i] > 0) {
                        freed.add(new Extent(node.keyOverflowOffset[i], Format.align8(8L + node.keyOverflowLength[i])));
                    }
                }
            }
            if (node.idsOverflowOffset != null) {
                for (int i = 0; i < node.idsOverflowOffset.length; i++) {
                    if (node.idsOverflowOffset[i] > 0) {
                        freed.add(new Extent(node.idsOverflowOffset[i], Format.align8(8L + node.idsOverflowLength[i])));
                    }
                }
            }
        }

        private static Node branch(byte[] separator, Node left, Node right) {
            Node root = new Node();
            root.dirty = true;
            root.keys = new byte[][]{separator};
            root.childOffsets = new long[]{0, 0};
            root.children = new Node[]{left, right};
            return root;
        }

        private NexException duplicate(Schema schema, Object[] values) {
            String kind = primary ? "chave primária"
                    : generatedUnique() ? "chave única"
                    : "índice " + name;
            return new NexException("valor duplicado para " + kind + " em " + schema.name + ": " + Arrays.toString(values));
        }

        private boolean generatedUnique() {
            if (primary || !name.startsWith("UNIQUE_")) {
                return false;
            }
            String suffix = name.substring("UNIQUE_".length());
            if (suffix.isEmpty()) {
                return false;
            }
            for (int i = 0; i < suffix.length(); i++) {
                if (!Character.isDigit(suffix.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        private NexException corrupted(Schema schema) {
            return new NexException("índice corrompido na tabela " + schema.name);
        }
    }

    interface KeyReader {
        Object[] parts(Schema schema, DiskIndex index, StoredRow row);
    }

    private record Split(Node left, byte[] separator, Node right) {
    }

    private static final class Node {
        boolean leaf;
        boolean dirty;
        long diskOffset;
        byte[][] keys = new byte[0][];
        long[][] ids = new long[0][];
        long[] childOffsets = new long[0];
        Node[] children;
        long[] keyOverflowOffset = new long[0];
        int[] keyOverflowLength = new int[0];
        long[] idsOverflowOffset = new long[0];
        int[] idsOverflowLength = new int[0];

        static Node leaf(byte[] key, long[] rowIds) {
            Node node = new Node();
            node.leaf = true;
            node.dirty = true;
            node.keys = new byte[][]{key};
            node.ids = new long[][]{rowIds};
            return node;
        }

        Node copy() {
            Node copy = new Node();
            copy.leaf = leaf;
            copy.dirty = true;
            copy.keys = keys.clone();
            copy.ids = ids.clone();
            copy.childOffsets = childOffsets.clone();
            copy.children = children == null ? null : children.clone();
            return copy;
        }

        int exact(Schema schema, int[] columns, byte[] key) {
            int lo = 0;
            int hi = keys.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                int cmp = IndexKeys.compare(schema, columns, keys[mid], key);
                if (cmp < 0) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            if (lo < keys.length && IndexKeys.compare(schema, columns, keys[lo], key) == 0) {
                return lo;
            }
            return -1;
        }

        int childIndex(Schema schema, int[] columns, byte[] key) {
            int lo = 0;
            int hi = keys.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (IndexKeys.compare(schema, columns, keys[mid], key) <= 0) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }

        void put(Schema schema, int[] columns, byte[] key, long rowId) {
            alignOverflow();
            int at = exact(schema, columns, key);
            if (at >= 0) {
                ids[at] = append(ids[at], rowId);
                return;
            }
            int lo = 0;
            int hi = keys.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (IndexKeys.compare(schema, columns, keys[mid], key) < 0) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            keys = insertKey(keys, lo, key);
            ids = insertIds(ids, lo, new long[]{rowId});
            keyOverflowOffset = insertLong(keyOverflowOffset, lo, 0);
            keyOverflowLength = insertInt(keyOverflowLength, lo, 0);
            idsOverflowOffset = insertLong(idsOverflowOffset, lo, 0);
            idsOverflowLength = insertInt(idsOverflowLength, lo, 0);
        }

        boolean remove(Schema schema, int[] columns, byte[] key, long rowId) {
            alignOverflow();
            int at = exact(schema, columns, key);
            if (at < 0) {
                return false;
            }
            long[] kept = without(ids[at], rowId);
            if (kept == null) {
                return false;
            }
            if (kept.length == 0) {
                keys = removeKey(keys, at);
                ids = removeIds(ids, at);
                keyOverflowOffset = removeLong(keyOverflowOffset, at);
                keyOverflowLength = removeInt(keyOverflowLength, at);
                idsOverflowOffset = removeLong(idsOverflowOffset, at);
                idsOverflowLength = removeInt(idsOverflowLength, at);
            } else {
                ids[at] = kept;
            }
            return true;
        }

        void insertChild(int at, byte[] separator, Node right) {
            keys = insertKey(keys, at, separator);
            Node[] nextChildren = new Node[childOffsets.length + 1];
            long[] nextOffsets = new long[childOffsets.length + 1];
            for (int i = 0, target = 0; i < childOffsets.length; i++, target++) {
                if (i == at + 1) {
                    nextChildren[target] = right;
                    nextOffsets[target] = 0;
                    target++;
                }
                nextChildren[target] = children == null ? null : children[i];
                nextOffsets[target] = childOffsets[i];
            }
            if (at + 1 == childOffsets.length) {
                nextChildren[at + 1] = right;
                nextOffsets[at + 1] = 0;
            }
            children = nextChildren;
            childOffsets = nextOffsets;
        }

        Node removeChild(int at) {
            int keyAt = at == 0 ? 0 : at - 1;
            if (keys.length == 0) {
                return null;
            }
            keys = removeKey(keys, Math.min(keyAt, keys.length - 1));
            Node[] nextChildren = new Node[childOffsets.length - 1];
            long[] nextOffsets = new long[childOffsets.length - 1];
            for (int i = 0, target = 0; i < childOffsets.length; i++) {
                if (i == at) {
                    continue;
                }
                nextChildren[target] = children == null ? null : children[i];
                nextOffsets[target] = childOffsets[i];
                target++;
            }
            children = nextChildren;
            childOffsets = nextOffsets;
            return this;
        }

        Split splitLeaf() {
            int mid = Math.max(1, keys.length / 2);
            if (mid >= keys.length) {
                throw new NexException("índice corrompido");
            }
            Node right = new Node();
            right.leaf = true;
            right.dirty = true;
            right.keys = Arrays.copyOfRange(keys, mid, keys.length);
            right.ids = Arrays.copyOfRange(ids, mid, ids.length);
            keys = Arrays.copyOf(keys, mid);
            ids = Arrays.copyOf(ids, mid);
            clearOverflow();
            right.clearOverflow();
            return new Split(this, right.keys[0], right);
        }

        Split splitInternal() {
            int mid = keys.length / 2;
            byte[] separator = keys[mid];
            Node right = new Node();
            right.dirty = true;
            right.keys = Arrays.copyOfRange(keys, mid + 1, keys.length);
            right.childOffsets = Arrays.copyOfRange(childOffsets, mid + 1, childOffsets.length);
            right.children = children == null ? new Node[right.childOffsets.length]
                    : Arrays.copyOfRange(children, mid + 1, children.length);
            keys = Arrays.copyOf(keys, mid);
            childOffsets = Arrays.copyOf(childOffsets, mid + 1);
            children = children == null ? new Node[childOffsets.length] : Arrays.copyOf(children, mid + 1);
            return new Split(this, separator, right);
        }

        private void clearOverflow() {
            alignOverflow();
        }

        private void alignOverflow() {
            if (keyOverflowOffset.length == keys.length && idsOverflowOffset.length == keys.length) {
                return;
            }
            keyOverflowOffset = new long[keys.length];
            keyOverflowLength = new int[keys.length];
            idsOverflowOffset = new long[keys.length];
            idsOverflowLength = new int[keys.length];
        }
    }

    private static final class Pages {
        private static final int KEY_INLINE = 700;
        private static final int ID_INLINE = 32;
        private static final int OVERFLOW = 0xFFFF;

        private Pages() {
        }

        static boolean fits(Node node) {
            return size(node) <= Format.INDEX_PAGE - 4;
        }

        static int size(Node node) {
            int size = 8;
            if (node.leaf) {
                for (int i = 0; i < node.keys.length; i++) {
                    size += entrySize(node.keys[i], node.ids[i]);
                }
                return size;
            }
            for (byte[] key : node.keys) {
                size += keySize(key);
            }
            return size + node.childOffsets.length * 8;
        }

        static Node read(PageSource source, long offset) {
            byte[] page = source.read(offset, Format.INDEX_PAGE);
            if (page.length != Format.INDEX_PAGE) {
                throw new NexException("índice corrompido");
            }
            int stored = ByteBuffer.wrap(page, Format.INDEX_PAGE - 4, 4).order(Format.ORDER).getInt();
            int actual = Format.crc(page, 0, Format.INDEX_PAGE - 4);
            if (stored != actual) {
                throw new NexException("índice corrompido");
            }
            ByteBuffer buffer = ByteBuffer.wrap(page).order(Format.ORDER);
            if (buffer.getInt() != Format.INDEX_MAGIC) {
                throw new NexException("índice corrompido");
            }
            int flags = buffer.getShort() & 0xFFFF;
            int count = buffer.getShort() & 0xFFFF;
            Node node = new Node();
            node.diskOffset = offset;
            node.leaf = (flags & 1) != 0;
            node.keys = new byte[count][];
            if (node.leaf) {
                node.ids = new long[count][];
                node.keyOverflowOffset = new long[count];
                node.keyOverflowLength = new int[count];
                node.idsOverflowOffset = new long[count];
                node.idsOverflowLength = new int[count];
                for (int i = 0; i < count; i++) {
                    node.keys[i] = readKey(buffer, source, node, i);
                    node.ids[i] = readIds(buffer, source, node, i);
                }
            } else {
                node.keyOverflowOffset = new long[count];
                node.keyOverflowLength = new int[count];
                for (int i = 0; i < count; i++) {
                    node.keys[i] = readKey(buffer, source, node, i);
                }
                node.childOffsets = new long[count + 1];
                node.children = new Node[count + 1];
                for (int i = 0; i < node.childOffsets.length; i++) {
                    node.childOffsets[i] = buffer.getLong();
                }
            }
            return node;
        }

        static byte[] encode(Node node, long[] children, Space space, List<TableStore.Write> writes) {
            byte[] page = new byte[Format.INDEX_PAGE];
            ByteBuffer buffer = ByteBuffer.wrap(page).order(Format.ORDER);
            buffer.putInt(Format.INDEX_MAGIC);
            buffer.putShort((short) (node.leaf ? 1 : 0));
            buffer.putShort((short) node.keys.length);
            if (node.leaf) {
                for (int i = 0; i < node.keys.length; i++) {
                    writeKey(buffer, node.keys[i], space, writes);
                    writeIds(buffer, node.ids[i], space, writes);
                }
            } else {
                for (byte[] key : node.keys) {
                    writeKey(buffer, key, space, writes);
                }
                if (children == null || children.length != node.keys.length + 1) {
                    throw new NexException("índice corrompido");
                }
                for (long child : children) {
                    if (child <= 0) {
                        throw new NexException("índice corrompido");
                    }
                    buffer.putLong(child);
                }
            }
            if (buffer.position() > Format.INDEX_PAGE - 4) {
                throw new NexException("índice corrompido");
            }
            int crc = Format.crc(page, 0, Format.INDEX_PAGE - 4);
            ByteBuffer.wrap(page).order(Format.ORDER).putInt(Format.INDEX_PAGE - 4, crc);
            return page;
        }

        private static int entrySize(byte[] key, long[] ids) {
            return keySize(key) + (ids.length <= ID_INLINE ? 2 + ids.length * 8 : 2 + 8 + 4);
        }

        private static int keySize(byte[] key) {
            return key.length <= KEY_INLINE ? 2 + key.length : 2 + 8 + 4;
        }

        private static byte[] readKey(ByteBuffer buffer, PageSource source, Node node, int index) {
            int length = buffer.getShort() & 0xFFFF;
            if (length != OVERFLOW) {
                byte[] key = new byte[length];
                buffer.get(key);
                return key;
            }
            long offset = buffer.getLong();
            int stored = buffer.getInt();
            node.keyOverflowOffset[index] = offset;
            node.keyOverflowLength[index] = stored;
            return overflow(source, offset, stored);
        }

        private static long[] readIds(ByteBuffer buffer, PageSource source, Node node, int index) {
            int count = buffer.getShort() & 0xFFFF;
            if (count != OVERFLOW) {
                long[] ids = new long[count];
                for (int i = 0; i < count; i++) {
                    ids[i] = buffer.getLong();
                }
                return ids;
            }
            long offset = buffer.getLong();
            int stored = buffer.getInt();
            node.idsOverflowOffset[index] = offset;
            node.idsOverflowLength[index] = stored;
            byte[] payload = overflow(source, offset, stored);
            ByteBuffer ids = ByteBuffer.wrap(payload).order(Format.ORDER);
            int total = stored / 8;
            long[] values = new long[total];
            for (int i = 0; i < total; i++) {
                values[i] = ids.getLong();
            }
            return values;
        }

        private static void writeKey(ByteBuffer buffer, byte[] key, Space space, List<TableStore.Write> writes) {
            if (key.length <= KEY_INLINE) {
                buffer.putShort((short) key.length);
                buffer.put(key);
                return;
            }
            long offset = spill(key, space, writes);
            buffer.putShort((short) OVERFLOW);
            buffer.putLong(offset);
            buffer.putInt(key.length);
        }

        private static void writeIds(ByteBuffer buffer, long[] ids, Space space, List<TableStore.Write> writes) {
            if (ids.length <= ID_INLINE) {
                buffer.putShort((short) ids.length);
                for (long id : ids) {
                    buffer.putLong(id);
                }
                return;
            }
            byte[] payload = new byte[ids.length * 8];
            ByteBuffer encoded = ByteBuffer.wrap(payload).order(Format.ORDER);
            for (long id : ids) {
                encoded.putLong(id);
            }
            long offset = spill(payload, space, writes);
            buffer.putShort((short) OVERFLOW);
            buffer.putLong(offset);
            buffer.putInt(payload.length);
        }

        private static long spill(byte[] payload, Space space, List<TableStore.Write> writes) {
            int size = (int) Format.align8(8L + payload.length);
            byte[] block = new byte[size];
            ByteBuffer buffer = ByteBuffer.wrap(block).order(Format.ORDER);
            buffer.putInt(Format.INDEX_OVERFLOW_MAGIC);
            buffer.putInt(payload.length);
            buffer.put(payload);
            long offset = space.allocate(size);
            writes.add(new TableStore.Write(offset, block));
            return offset;
        }

        private static byte[] overflow(PageSource source, long offset, int length) {
            int size = (int) Format.align8(8L + length);
            byte[] block = source.read(offset, size);
            ByteBuffer buffer = ByteBuffer.wrap(block).order(Format.ORDER);
            if (buffer.getInt() != Format.INDEX_OVERFLOW_MAGIC || buffer.getInt() != length) {
                throw new NexException("índice corrompido");
            }
            byte[] payload = new byte[length];
            buffer.get(payload);
            return payload;
        }
    }

    private static final class IndexKeys {
        private IndexKeys() {
        }

        static byte[] encode(Schema schema, int[] columns, Object[] values) {
            try {
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(raw);
                for (int i = 0; i < columns.length; i++) {
                    write(out, schema.columns.get(columns[i]), values[i]);
                }
                out.flush();
                return raw.toByteArray();
            } catch (IOException exception) {
                throw new NexException("índice corrompido na tabela " + schema.name);
            }
        }

        static int compare(Schema schema, int[] columns, byte[] left, byte[] right) {
            if (!orderedBytes(schema, columns)) {
                return compareDecoded(schema, columns, left, right);
            }
            int a = 0;
            int b = 0;
            for (int column : columns) {
                DataType type = schema.columns.get(column).type;
                int cmp = compareFixed(type, left, a, right, b);
                if (cmp != 0) {
                    return cmp;
                }
                int width = width(type);
                a += width;
                b += width;
            }
            return 0;
        }

        private static int compareDecoded(Schema schema, int[] columns, byte[] left, byte[] right) {
            Object[] a = decode(schema, columns, left);
            Object[] b = decode(schema, columns, right);
            for (int i = 0; i < columns.length; i++) {
                int cmp = Values.compare(a[i], b[i]);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        }

        private static boolean orderedBytes(Schema schema, int[] columns) {
            for (int column : columns) {
                if (width(schema.columns.get(column).type) == 0) {
                    return false;
                }
            }
            return true;
        }

        private static int width(DataType type) {
            return switch (type) {
                case BOOLEAN -> 1;
                case SMALLINT -> 2;
                case INTEGER, DATE -> 4;
                case BIGINT, TIME -> 8;
                case TIMESTAMP -> 12;
                default -> 0;
            };
        }

        private static int compareFixed(DataType type, byte[] left, int a, byte[] right, int b) {
            return switch (type) {
                case BOOLEAN -> Boolean.compare(left[a] != 0, right[b] != 0);
                case SMALLINT -> Short.compare(readShort(left, a), readShort(right, b));
                case INTEGER, DATE -> Integer.compare(readInt(left, a), readInt(right, b));
                case BIGINT, TIME -> Long.compare(readLong(left, a), readLong(right, b));
                case TIMESTAMP -> {
                    int cmp = Long.compare(readLong(left, a), readLong(right, b));
                    yield cmp != 0 ? cmp : Integer.compare(readInt(left, a + 8), readInt(right, b + 8));
                }
                default -> 0;
            };
        }

        private static short readShort(byte[] raw, int offset) {
            return (short) ((raw[offset] << 8) | (raw[offset + 1] & 0xFF));
        }

        private static int readInt(byte[] raw, int offset) {
            return (raw[offset] << 24)
                    | ((raw[offset + 1] & 0xFF) << 16)
                    | ((raw[offset + 2] & 0xFF) << 8)
                    | (raw[offset + 3] & 0xFF);
        }

        private static long readLong(byte[] raw, int offset) {
            return ((long) readInt(raw, offset) << 32) | (readInt(raw, offset + 4) & 0xFFFFFFFFL);
        }

        private static Object[] decode(Schema schema, int[] columns, byte[] encoded) {
            ByteBuffer buffer = ByteBuffer.wrap(encoded).order(Format.ORDER);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                values[i] = read(buffer, schema.columns.get(columns[i]));
            }
            if (buffer.hasRemaining()) {
                throw new NexException("índice corrompido na tabela " + schema.name);
            }
            return values;
        }

        private static void write(DataOutputStream out, Column column, Object value) throws IOException {
            switch (column.type) {
                case SMALLINT -> out.writeShort(((Number) value).shortValue());
                case INTEGER -> out.writeInt(((Number) value).intValue());
                case BIGINT -> out.writeLong(((Number) value).longValue());
                case FLOAT -> out.writeFloat(((Number) value).floatValue());
                case DOUBLE -> out.writeDouble(((Number) value).doubleValue());
                case BOOLEAN -> out.writeBoolean((Boolean) value);
                case CHAR, VARCHAR -> {
                    byte[] text = ((String) value).getBytes(StandardCharsets.UTF_8);
                    out.writeShort(text.length);
                    out.write(text);
                }
                case DATE -> out.writeInt((int) ((LocalDate) value).toEpochDay());
                case TIME -> out.writeLong(((LocalTime) value).toNanoOfDay());
                case TIMESTAMP -> {
                    LocalDateTime timestamp = (LocalDateTime) value;
                    out.writeLong(timestamp.toEpochSecond(ZoneOffset.UTC));
                    out.writeInt(timestamp.getNano());
                }
                case DECIMAL -> {
                    BigDecimal decimal = (BigDecimal) value;
                    byte[] unscaled = decimal.unscaledValue().toByteArray();
                    out.writeInt(decimal.scale());
                    out.writeShort(unscaled.length);
                    out.write(unscaled);
                }
                case BLOB -> throw new NexException("BLOB não pode ser chave");
            }
        }

        private static Object read(ByteBuffer buffer, Column column) {
            return switch (column.type) {
                case SMALLINT -> buffer.getShort();
                case INTEGER -> buffer.getInt();
                case BIGINT -> buffer.getLong();
                case FLOAT -> buffer.getFloat();
                case DOUBLE -> buffer.getDouble();
                case BOOLEAN -> buffer.get() != 0;
                case CHAR, VARCHAR -> {
                    int length = buffer.getShort() & 0xFFFF;
                    byte[] text = new byte[length];
                    buffer.get(text);
                    yield new String(text, StandardCharsets.UTF_8);
                }
                case DATE -> LocalDate.ofEpochDay(buffer.getInt());
                case TIME -> LocalTime.ofNanoOfDay(buffer.getLong());
                case TIMESTAMP -> {
                    long seconds = buffer.getLong();
                    int nano = buffer.getInt();
                    yield LocalDateTime.ofEpochSecond(seconds, nano, ZoneOffset.UTC);
                }
                case DECIMAL -> {
                    int scale = buffer.getInt();
                    int length = buffer.getShort() & 0xFFFF;
                    byte[] unscaled = new byte[length];
                    buffer.get(unscaled);
                    yield new BigDecimal(new BigInteger(unscaled), scale);
                }
                case BLOB -> throw new NexException("BLOB não pode ser chave");
            };
        }
    }

    private static boolean contains(long[] ids, long rowId) {
        for (long id : ids) {
            if (id == rowId) {
                return true;
            }
        }
        return false;
    }

    private static long[] append(long[] ids, long rowId) {
        long[] next = Arrays.copyOf(ids, ids.length + 1);
        next[ids.length] = rowId;
        return next;
    }

    private static long[] without(long[] ids, long rowId) {
        int at = -1;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == rowId) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return null;
        }
        long[] next = new long[ids.length - 1];
        System.arraycopy(ids, 0, next, 0, at);
        System.arraycopy(ids, at + 1, next, at, ids.length - at - 1);
        return next;
    }

    private static byte[][] insertKey(byte[][] keys, int at, byte[] key) {
        byte[][] next = new byte[keys.length + 1][];
        System.arraycopy(keys, 0, next, 0, at);
        next[at] = key;
        System.arraycopy(keys, at, next, at + 1, keys.length - at);
        return next;
    }

    private static long[][] insertIds(long[][] ids, int at, long[] rowIds) {
        long[][] next = new long[ids.length + 1][];
        System.arraycopy(ids, 0, next, 0, at);
        next[at] = rowIds;
        System.arraycopy(ids, at, next, at + 1, ids.length - at);
        return next;
    }

    private static byte[][] removeKey(byte[][] keys, int at) {
        byte[][] next = new byte[keys.length - 1][];
        System.arraycopy(keys, 0, next, 0, at);
        System.arraycopy(keys, at + 1, next, at, keys.length - at - 1);
        return next;
    }

    private static long[][] removeIds(long[][] ids, int at) {
        long[][] next = new long[ids.length - 1][];
        System.arraycopy(ids, 0, next, 0, at);
        System.arraycopy(ids, at + 1, next, at, ids.length - at - 1);
        return next;
    }

    private static long[] insertLong(long[] values, int at, long value) {
        if (values.length == 0 && at == 0) {
            return new long[]{value};
        }
        long[] next = new long[values.length + 1];
        if (values.length > 0) {
            System.arraycopy(values, 0, next, 0, Math.min(at, values.length));
            if (at < values.length) {
                System.arraycopy(values, at, next, at + 1, values.length - at);
            }
        }
        next[at] = value;
        return next;
    }

    private static int[] insertInt(int[] values, int at, int value) {
        int[] next = new int[values.length + 1];
        if (values.length > 0) {
            System.arraycopy(values, 0, next, 0, Math.min(at, values.length));
            if (at < values.length) {
                System.arraycopy(values, at, next, at + 1, values.length - at);
            }
        }
        next[at] = value;
        return next;
    }

    private static long[] removeLong(long[] values, int at) {
        if (values.length == 0) {
            return values;
        }
        long[] next = new long[values.length - 1];
        System.arraycopy(values, 0, next, 0, at);
        System.arraycopy(values, at + 1, next, at, values.length - at - 1);
        return next;
    }

    private static int[] removeInt(int[] values, int at) {
        if (values.length == 0) {
            return values;
        }
        int[] next = new int[values.length - 1];
        System.arraycopy(values, 0, next, 0, at);
        System.arraycopy(values, at + 1, next, at, values.length - at - 1);
        return next;
    }
}
