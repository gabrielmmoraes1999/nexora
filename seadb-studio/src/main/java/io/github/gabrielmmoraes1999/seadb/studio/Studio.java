package io.github.gabrielmmoraes1999.seadb.studio;

import javax.swing.SwingUtilities;

/**
 * Administrative interface for SeaDB.
 *
 * <pre>{@code
 * java -jar seadb-studio-1.0.jar
 * }</pre>
 */
public final class Studio {

    private Studio() {
    }

    public static void main(String[] args) {
        try {
            Class.forName("io.github.gabrielmmoraes1999.seadb.jdbc.SeaDriver");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("SeaDB JDBC driver not found", exception);
        }
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
                SwingUtilities.invokeLater(() -> Dialogs.error(null, Dialogs.message(error))));
        Look.install();
        SwingUtilities.invokeLater(() -> new StudioFrame().setVisible(true));
    }
}
