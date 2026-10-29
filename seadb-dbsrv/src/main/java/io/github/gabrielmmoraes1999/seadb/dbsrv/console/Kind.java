package io.github.gabrielmmoraes1999.seadb.dbsrv.console;

/**
 * The two server windows. The personal server is dbeng: it listens on this machine.
 * The network server is dbsrv: it listens for other machines as well.
 */
public enum Kind {

    PERSONAL("SeaDB Personal Server"),
    NETWORK("SeaDB Network Server");

    private final String product;

    Kind(String product) {
        this.product = product;
    }

    public String product() {
        return product;
    }

    public String defaultHost() {
        return this == PERSONAL ? "127.0.0.1" : "0.0.0.0";
    }

    public String jar() {
        return this == PERSONAL ? "seadb-dbeng-1.0.jar" : "seadb-dbsrv-1.0.jar";
    }
}
