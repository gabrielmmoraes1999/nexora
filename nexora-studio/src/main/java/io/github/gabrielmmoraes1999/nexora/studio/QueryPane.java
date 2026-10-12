package io.github.gabrielmmoraes1999.nexora.studio;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextPane;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

final class QueryPane extends JPanel {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Hub hub;
    private final String databaseId;
    private final JTextPane editor = new SqlEditor();
    private final JTable grid = Tables.create();
    private final JTextPane log = new JTextPane();
    private final JLabel summary = new JLabel(" ");
    private final JTabbedPane bottom = new JTabbedPane();

    QueryPane(Hub hub, String databaseId, String sql) {
        super(new BorderLayout());
        this.hub = hub;
        this.databaseId = databaseId;
        editor.setFont(Look.MONO);
        editor.setBackground(Look.EDITOR);
        editor.setText(sql == null ? "" : sql);
        editor.setCaretPosition(editor.getDocument().getLength());
        Highlighter.watch(editor);
        JScrollPane editorScroll = Look.scroll(editor);
        editorScroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());

        log.setEditable(false);
        log.setFont(Look.MONO.deriveFont(12f));
        log.setBackground(java.awt.Color.WHITE);
        Tables.install(grid);
        bottom.addTab("Resultado", Look.scroll(grid));
        bottom.addTab("Mensagens", Look.scroll(log));

        JPanel south = new JPanel(new BorderLayout());
        summary.setFont(Look.UI);
        summary.setForeground(Look.MUTED);
        summary.setBorder(new EmptyBorder(4, 8, 4, 8));
        south.add(summary, BorderLayout.NORTH);
        south.add(bottom, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorScroll, south);
        split.setResizeWeight(0.55);
        split.setDividerSize(6);
        split.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        add(toolbar(), BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        message(false, "F9 executa o script. O SELECT, quando houver, precisa ser o último comando.");
    }

    String databaseId() {
        return databaseId;
    }

    void execute() {
        Connection connection = hub.connection(databaseId);
        if (connection == null) {
            message(true, "banco desconectado");
            bottom.setSelectedIndex(1);
            return;
        }
        String sql = selectedSql();
        if (sql.isBlank()) {
            message(true, "escreva um comando");
            bottom.setSelectedIndex(1);
            return;
        }
        hub.owner().setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        long started = System.nanoTime();
        try (Statement statement = connection.createStatement()) {
            statement.setMaxRows(1000);
            boolean query = statement.execute(sql);
            long millis = (System.nanoTime() - started) / 1_000_000L;
            if (query) {
                try (ResultSet result = statement.getResultSet()) {
                    int rows = Tables.showResult(grid, result);
                    String capped = rows == 1000 ? " (limite de 1000)" : "";
                    summary.setText(rows + (rows == 1 ? " linha" : " linhas") + " em " + millis + " ms" + capped);
                    message(false, "consulta  " + summary.getText());
                    bottom.setSelectedIndex(0);
                }
            } else {
                int count = statement.getUpdateCount();
                summary.setText(count + " linha(s) afetada(s) em " + millis + " ms");
                message(false, "comando  " + summary.getText());
                grid.setModel(new javax.swing.table.DefaultTableModel());
                bottom.setSelectedIndex(1);
            }
            if (SqlNames.changesCatalog(sql)) {
                hub.refreshOutline(databaseId);
            }
            hub.message(summary.getText());
        } catch (SQLException exception) {
            summary.setText(Dialogs.message(exception));
            message(true, Dialogs.message(exception));
            bottom.setSelectedIndex(1);
            hub.message(Dialogs.message(exception));
        } finally {
            hub.owner().setCursor(java.awt.Cursor.getDefaultCursor());
        }
    }

    private JPanel toolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.setBackground(new java.awt.Color(0xF7F8FA));
        javax.swing.JButton run = Look.primary("Executar");
        run.setToolTipText("Executar o script (F9)");
        run.addActionListener(event -> execute());
        javax.swing.JButton copy = Look.button("Copiar grade");
        copy.addActionListener(event -> Tables.copy(grid));
        bar.add(run);
        bar.add(copy);
        return bar;
    }

    private String selectedSql() {
        String selected = editor.getSelectedText();
        if (selected != null && !selected.isBlank()) {
            return selected;
        }
        return editor.getText();
    }

    private void message(boolean error, String text) {
        SimpleAttributeSet set = new SimpleAttributeSet();
        StyleConstants.setForeground(set, error ? Look.ERROR : Look.INK);
        StyleConstants.setFontFamily(set, Look.MONO.getFamily());
        StyleConstants.setFontSize(set, 12);
        try {
            StyledDocument document = log.getStyledDocument();
            document.insertString(document.getLength(), LocalTime.now().format(CLOCK) + "  " + text + "\n", set);
        } catch (BadLocationException exception) {
            log.setText(text);
        }
    }

    private static final class SqlEditor extends JTextPane {
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return getParent() instanceof javax.swing.JViewport viewport
                    && viewport.getWidth() > getUI().getRootView(this).getPreferredSpan(javax.swing.text.View.X_AXIS);
        }
    }
}
