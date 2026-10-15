package io.github.gabrielmmoraes1999.nexora.server;

import io.github.gabrielmmoraes1999.nexora.NexException;

import java.nio.file.Path;

/**
 * Command line for the network server.
 *
 * <pre>{@code
 * java -jar nexora-server-1.0.jar serve motor.json
 * }</pre>
 */
public final class Motor {

    private Motor() {
    }

    public static void main(String[] args) {
        try {
            execute(args);
        } catch (NexException exception) {
            System.err.println(exception.getMessage());
            System.exit(1);
        }
    }

    public static void execute(String[] args) {
        if (args != null && args.length > 0 && help(args[0])) {
            System.out.println(uso());
            return;
        }
        if (args == null || args.length == 0 || !args[0].equalsIgnoreCase("serve")) {
            throw new NexException(uso());
        }
        if (args.length != 2 || args[1].isBlank()) {
            throw new NexException("provide the server json file\n\n" + uso());
        }
        MotorConfig config = MotorConfig.read(Path.of(args[1]));
        MotorServer server = MotorServer.start(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "nex-motor-shutdown"));
        System.out.println("server at " + config.host() + ":" + server.port());
        for (MotorConfig.DatabaseSpec spec : config.databases()) {
            System.out.println("database " + spec.name() + ": " + spec.path());
        }
        try {
            server.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            server.close();
        }
    }

    static String uso() {
        return """
                Usage:
                  java -jar nexora-server-1.0.jar serve <server.json>

                Publishes the databases from the json to jdbc:nexora://host:port/name.

                Example:
                  java -jar nexora-server-1.0.jar serve motor.json
                """;
    }

    private static boolean help(String arg) {
        return arg.equalsIgnoreCase("--help") || arg.equalsIgnoreCase("-h");
    }
}
