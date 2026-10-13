package io.github.gabrielmmoraes1999.nexora;

import java.net.InetAddress;

/**
 * Origem de uma conexão, no papel de {@code MON$REMOTE_*} do Firebird.
 * Na conexão embutida o protocolo é {@code local} e o endereço fica vazio.
 * No motor, protocolo e endereço vêm do socket; host, usuário do sistema
 * e processo vêm do cliente.
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
            // sem nome de máquina, a coluna fica nula
        }
        String process = ProcessHandle.current().info().command().orElse(null);
        return new RemoteClient("local", null, host, System.getProperty("user.name"), process);
    }
}
