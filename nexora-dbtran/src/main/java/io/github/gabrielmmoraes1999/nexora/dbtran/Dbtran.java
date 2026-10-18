package io.github.gabrielmmoraes1999.nexora.dbtran;

import io.github.gabrielmmoraes1999.nexora.NexException;

import javax.swing.SwingUtilities;
import java.nio.file.Path;

/**
 * Translates a transaction log into SQL.
 * With no arguments, the window opens. With a log and a SQL file, the command writes the script.
 *
 * <pre>{@code
 * java -jar nexora-dbtran-1.0.jar
 * java -jar nexora-dbtran-1.0.jar store.log C:/data/store.sql
 * java -jar nexora-dbtran-1.0.jar store.db C:/data/store.sql
 * }</pre>
 */
public final class Dbtran {

    private Dbtran() {
    }

    public static void main(String[] args) {
        if (args != null && args.length > 0) {
            try {
                execute(args);
            } catch (NexException exception) {
                System.err.println(exception.getMessage());
                System.exit(1);
            }
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
                SwingUtilities.invokeLater(() -> DbtranFrame.report(null, error)));
        Look.install();
        SwingUtilities.invokeLater(() -> new DbtranFrame().setVisible(true));
    }

    /**
     * Writes the SQL from {@code args[0]} into {@code args[1]}.
     * {@code --help} prints the usage. A {@code .db} uses the {@code .log} beside it.
     */
    public static void execute(String[] args) {
        if (args != null && args.length > 0 && help(args[0])) {
            System.out.println(usage());
            return;
        }
        if (args == null || args.length != 2 || args[0].isBlank() || args[1].isBlank()) {
            throw new NexException(usage());
        }
        Path sql = Path.of(args[1]);
        int[] shown = {-1};
        String written = DbtranService.export(args[0], sql, percent -> {
            if (percent == shown[0]) {
                return;
            }
            shown[0] = percent;
            System.out.print("\rexporting: " + percent + "%");
            System.out.flush();
        });
        System.out.println();
        System.out.println(written);
    }

    static String usage() {
        return """
                Usage:
                  java -jar nexora-dbtran-1.0.jar
                  java -jar nexora-dbtran-1.0.jar <file.log> <file.sql>

                With no arguments, the window opens.
                <file.log> may be the .db; the .log beside it is read.
                The command shows the percentage while it reads the file.
                Parameters are written into the text in place of each ?.

                Example:
                  java -jar nexora-dbtran-1.0.jar C:/data/store.log C:/data/store.sql
                  java -jar nexora-dbtran-1.0.jar C:/data/store.db C:/data/store.sql
                """;
    }

    private static boolean help(String arg) {
        return arg.equalsIgnoreCase("--help") || arg.equalsIgnoreCase("-h");
    }
}
