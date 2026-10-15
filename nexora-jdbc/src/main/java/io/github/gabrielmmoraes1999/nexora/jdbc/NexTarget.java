package io.github.gabrielmmoraes1999.nexora.jdbc;

import io.github.gabrielmmoraes1999.nexora.NexException;

/**
 * Target of a JDBC URL: a local file or a database published by the server.
 */
final class NexTarget {

    private final boolean embedded;
    private final String path;
    private final String host;
    private final int port;
    private final String database;

    private NexTarget(boolean embedded, String path, String host, int port, String database) {
        this.embedded = embedded;
        this.path = path;
        this.host = host;
        this.port = port;
        this.database = database;
    }

    static NexTarget parse(String url) {
        if (url == null || !url.regionMatches(true, 0, NexDriver.URL_PREFIX, 0, NexDriver.URL_PREFIX.length())) {
            throw new NexException("invalid URL");
        }
        String rest = url.substring(NexDriver.URL_PREFIX.length()).trim();
        if (rest.regionMatches(true, 0, "embedded:", 0, "embedded:".length())) {
            return embedded(rest.substring("embedded:".length()).trim());
        }
        if (rest.startsWith("//")) {
            String after = rest.substring(2);
            if (windowsDrive(after)) {
                return embedded(after);
            }
            return server(after);
        }
        return embedded(rest);
    }

    boolean embedded() {
        return embedded;
    }

    String path() {
        return path;
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    String database() {
        return database;
    }

    private static NexTarget embedded(String path) {
        return new NexTarget(true, path, "", 0, "");
    }

    private static NexTarget server(String after) {
        String host;
        String tail;
        if (after.startsWith("[")) {
            int end = after.indexOf(']');
            if (end <= 1) {
                throw new NexException("specify the host in the URL jdbc:nexora://host:port/name");
            }
            host = after.substring(1, end).trim();
            tail = after.substring(end + 1);
            if (!tail.startsWith(":")) {
                throw new NexException("specify the port in the URL jdbc:nexora://host:port/name");
            }
            tail = tail.substring(1);
        } else {
            int slash = after.indexOf('/');
            String hostPort = slash < 0 ? after : after.substring(0, slash);
            int colon = hostPort.lastIndexOf(':');
            if (colon <= 0) {
                throw new NexException("specify the port in the URL jdbc:nexora://host:port/name");
            }
            host = hostPort.substring(0, colon).trim();
            tail = after.substring(colon + 1);
        }
        int slash = tail.indexOf('/');
        if (slash <= 0 || slash == tail.length() - 1) {
            throw new NexException("specify the database name in the URL jdbc:nexora://host:port/name");
        }
        String portText = tail.substring(0, slash).trim();
        String name = tail.substring(slash + 1).trim();
        if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('?') >= 0 || name.indexOf('#') >= 0) {
            throw new NexException("specify the database name in the URL jdbc:nexora://host:port/name");
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException exception) {
            throw new NexException("invalid port in the URL jdbc:nexora://host:port/name");
        }
        if (port < 1 || port > 65535) {
            throw new NexException("invalid port in the URL jdbc:nexora://host:port/name");
        }
        if (host.isEmpty()) {
            throw new NexException("specify the host in the URL jdbc:nexora://host:port/name");
        }
        return new NexTarget(false, "", host, port, name);
    }

    private static boolean windowsDrive(String text) {
        return text.length() >= 2 && Character.isLetter(text.charAt(0)) && text.charAt(1) == ':';
    }
}
