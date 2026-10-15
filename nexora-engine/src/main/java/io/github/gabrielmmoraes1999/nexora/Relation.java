package io.github.gabrielmmoraes1999.nexora;

/**
 * Nome de tabela ou view. Sem esquema, {@code schema} fica {@code null}
 * até a resolução usar o esquema da sessão. O padrão é {@code public}.
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
     * Nome gravado no catálogo. Em {@code public} fica só o objeto.
     * Nos outros esquemas, {@code esquema.objeto}.
     */
    static String catalogName(String schema, String name) {
        if (schema == null || Names.same(schema, PUBLIC)) {
            return name;
        }
        return schema + "." + name;
    }

    static Relation parse(String written) {
        if (written == null || written.isBlank()) {
            throw new NexException("nome é obrigatório");
        }
        int dot = written.indexOf('.');
        if (dot < 0) {
            return new Relation(null, written);
        }
        if (dot == 0 || dot == written.length() - 1 || written.indexOf('.', dot + 1) >= 0) {
            throw new NexException("nome qualificado inválido: " + written);
        }
        return new Relation(written.substring(0, dot), written.substring(dot + 1));
    }

    static Relation fromCatalog(String catalogName, String metaName) {
        if (Names.same(catalogName, metaName)) {
            return new Relation(PUBLIC, metaName);
        }
        int dot = catalogName.lastIndexOf('.');
        if (dot <= 0 || dot >= catalogName.length() - 1) {
            throw new NexException("catálogo inconsistente na tabela " + catalogName);
        }
        String schema = catalogName.substring(0, dot);
        String name = catalogName.substring(dot + 1);
        if (!Names.same(name, metaName)) {
            throw new NexException("catálogo inconsistente na tabela " + catalogName);
        }
        return new Relation(schema, metaName);
    }
}
