package io.github.gabrielmmoraes1999.nexora.server.console;

import io.github.gabrielmmoraes1999.nexora.NexException;

/**
 * Opens the startup window, then the message console.
 * With a database already on the command line, the startup window is skipped.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void start(Kind kind, String[] args) {
        try {
            Arguments arguments = Arguments.parse(kind, args);
            if (arguments.help()) {
                System.out.println(Arguments.usage(kind));
                return;
            }
            ServerConsole.installLook();
            if (arguments.databases().isEmpty()) {
                Arguments chosen = StartDialog.ask(kind, arguments);
                if (chosen == null) {
                    System.exit(0);
                    return;
                }
                arguments = chosen;
            }
            ServerConsole.open(arguments);
        } catch (NexException exception) {
            System.err.println(exception.getMessage());
            System.exit(1);
        }
    }
}
