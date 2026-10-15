package io.github.gabrielmmoraes1999.nexora;

/**
 * Table or view name. Without a schema, {@code schema} stays {@code null}
 * until resolution uses the session schema. The default is {@code public}.
 */
final class Relation {

    static final String PUBLIC = "public";

    private final String schema;
    private final String name;

    Relation(String schema, String name) {
        this.schema = schema;
        this.name = name;
    }

    String schema() {
        return schema;
    }

    String name() {
        return name;
    }

    String key() {
        return key(schema, name);
    }

    static String key(String schema, String name) {
        return Names.fold(schema) + "\0" + Names.fold(name);
    }

    /**
     * Name stored in the catalog. In {@code public} it is only the object.
     * In other schemas, {@code schema.object}.
     */
    static String catalogName(String schema, String name) {
        if (schema == null || Names.same(schema, PUBLIC)) {
            return name;
        }
        return schema + "." + name;
    }

    static Relation parse(String written) {
        if (written == null || written.isBlank()) {
            throw new NexException("name is required");
        }
        int dot = written.indexOf('.');
        if (dot < 0) {
            return new Relation(null, written);
        }
        if (dot == 0 || dot == written.length() - 1 || written.indexOf('.', dot + 1) >= 0) {
            throw new NexException("invalid qualified name: " + written);
        }
        return new Relation(written.substring(0, dot), written.substring(dot + 1));
    }

    static Relation fromCatalog(String catalogName, String metaName) {
        if (Names.same(catalogName, metaName)) {
            return new Relation(PUBLIC, metaName);
        }
        int dot = catalogName.lastIndexOf('.');
        if (dot <= 0 || dot >= catalogName.length() - 1) {
            throw new NexException("inconsistent catalog in table " + catalogName);
        }
        String schema = catalogName.substring(0, dot);
        String name = catalogName.substring(dot + 1);
        if (!Names.same(name, metaName)) {
            throw new NexException("inconsistent catalog in table " + catalogName);
        }
        return new Relation(schema, metaName);
    }
}
