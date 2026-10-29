package io.github.gabrielmmoraes1999.seadb;

import java.net.InetAddress;

/**
 * Origin of a connection.
 * On an embedded connection the protocol is {@code local} and the address is empty.
 * On the server, protocol and address come from the socket; host, system user,
 * and process come from the client.
 */
public record RemoteClient(String protocol, String address, String host, String osUser, String process) {

    private static final RemoteClient LOCAL = localProbe();

    public static RemoteClient local() {
        return LOCAL;
    }

    private static RemoteClient localProbe() {
        String host = null;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            // without a machine name, the column stays null
        }
        String process = ProcessHandle.current().info().command().orElse(null);
        return new RemoteClient("local", null, host, System.getProperty("user.name"), process);
    }
}
