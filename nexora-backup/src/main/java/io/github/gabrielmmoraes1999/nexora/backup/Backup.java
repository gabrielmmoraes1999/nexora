package io.github.gabrielmmoraes1999.nexora.backup;

import javax.swing.SwingUtilities;

/**
 * Backup window. Full backup and incremental backup.
 *
 * <pre>{@code
 * java -jar nexora-backup-1.0.jar
 * }</pre>
 */
public final class Backup {

    private Backup() {
    }

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
                SwingUtilities.invokeLater(() -> BackupFrame.report(null, error)));
        Look.install();
        SwingUtilities.invokeLater(() -> new BackupFrame().setVisible(true));
    }
}
