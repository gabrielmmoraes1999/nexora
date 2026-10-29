package io.github.gabrielmmoraes1999.seadb.dbtran;

import io.github.gabrielmmoraes1999.seadb.SeaException;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.function.IntConsumer;

/**
 * Window that writes the SQL from a transaction log.
 * The fields match the CLI: a {@code .log} or the {@code .db} beside it, then the {@code .sql} file.
 */
final class DbtranFrame extends JFrame {

    private final List<JComponent> locks = new ArrayList<>();
    private final JPanel form = new JPanel(new GridBagLayout());
    private final JLabel status = new JLabel(" ");
    private final JLabel percent = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JTextArea transcript = new JTextArea(7, 40);
    private final JButton run = Look.primary("Translate");

    private final JTextField sourceField = field();
    private final JTextField sqlField = field();

    private final JButton sourceBrowse = browse(this::chooseSource);
    private final JButton sqlBrowse = browse(this::chooseSql);

    private int row;
    private boolean busy;

    DbtranFrame() {
        super("SeaDB dbtran");
        status.setFont(Look.UI_BOLD);
        status.setForeground(Look.MUTED);
        percent.setFont(Look.UI);
        percent.setForeground(Look.MUTED);
        percent.setHorizontalAlignment(SwingConstants.RIGHT);
        percent.setPreferredSize(new Dimension(52, 22));
        progress.setValue(0);
        transcript.setEditable(false);
        transcript.setFont(Look.MONO);
        transcript.setLineWrap(true);
        transcript.setWrapStyleWord(true);
        transcript.setBorder(new EmptyBorder(8, 8, 8, 8));
        run.addActionListener(event -> start());
        form.setOpaque(false);
        form.setBorder(new EmptyBorder(16, 20, 8, 20));

        JPanel root = new JPanel(new BorderLayout());
        Look.paper(root);
        root.add(header(), BorderLayout.NORTH);
        JScrollPane formScroll = new JScrollPane(form);
        formScroll.setBorder(null);
        formScroll.setOpaque(false);
        formScroll.getViewport().setOpaque(false);
        formScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        root.add(formScroll, BorderLayout.CENTER);
        root.add(south(), BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(run);
        setIconImages(Look.iconImages());
        applySettings(DbtranSettings.load(DbtranSettings.file()));
        rebuild();
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                close();
            }
        });
        pack();
        setSize(Math.max(getWidth(), 760), Math.max(getHeight(), 520));
        setMinimumSize(new Dimension(680, 460));
        setLocationRelativeTo(null);
    }

    static void report(Component parent, Throwable error) {
        JOptionPane.showMessageDialog(parent, messageOf(error), "SeaDB dbtran", JOptionPane.ERROR_MESSAGE);
    }

    private JPanel header() {
        JPanel header = new JPanel();
        header.setLayout(new javax.swing.BoxLayout(header, javax.swing.BoxLayout.Y_AXIS));
        header.setOpaque(true);
        header.setBackground(Look.CHROME);
        header.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(0, 0, 1, 0, Look.LINE),
                new EmptyBorder(16, 20, 14, 20)));
        JLabel title = new JLabel("SeaDB dbtran");
        title.setFont(Look.UI.deriveFont(Font.BOLD, 20f));
        title.setForeground(Look.NAVY);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel subtitle = new JLabel("Writes the SQL from the transaction log.");
        subtitle.setForeground(Look.MUTED);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(title);
        header.add(Box.createVerticalStrut(2));
        header.add(subtitle);
        return header;
    }

    private JPanel south() {
        JPanel progressLine = new JPanel(new BorderLayout(8, 0));
        progressLine.setOpaque(false);
        progressLine.add(progress, BorderLayout.CENTER);
        progressLine.add(percent, BorderLayout.EAST);
        JPanel stack = new JPanel(new BorderLayout(0, 6));
        stack.setOpaque(false);
        stack.add(status, BorderLayout.NORTH);
        stack.add(progressLine, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(run);
        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.setOpaque(false);
        south.setBorder(new EmptyBorder(0, 20, 16, 20));
        south.add(stack, BorderLayout.NORTH);
        south.add(Look.scroll(transcript), BorderLayout.CENTER);
        south.add(buttons, BorderLayout.SOUTH);
        return south;
    }

    private void rebuild() {
        form.removeAll();
        row = 0;
        addRow(note("Reads the compressed transactions from the .log and writes the SQL. A .db uses the .log beside it. Parameters are written into the text."));
        addRow(labeled("Log or database", sourceField, sourceBrowse));
        addRow(labeled("SQL file", sqlField, sqlBrowse));
        GridBagConstraints glue = new GridBagConstraints();
        glue.gridx = 0;
        glue.gridy = row;
        glue.weighty = 1;
        form.add(Box.createVerticalGlue(), glue);
        form.revalidate();
        form.repaint();
    }

    private void start() {
        if (busy) {
            return;
        }
        try {
            validateForm();
        } catch (SeaException exception) {
            showError(exception.getMessage());
            return;
        }
        String source = sourceField.getText().trim();
        Path sql = Path.of(sqlField.getText().trim());
        setBusy(true);
        showPercent(0);
        status.setForeground(Look.MUTED);
        status.setText("Working...");
        append("Translating " + source);
        new SwingWorker<String, Integer>() {
            @Override
            protected String doInBackground() {
                IntConsumer callback = this::publishPercent;
                return DbtranService.export(source, sql, callback);
            }

            private void publishPercent(int value) {
                publish(value);
            }

            @Override
            protected void process(List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    showPercent(chunks.get(chunks.size() - 1));
                }
            }

            @Override
            protected void done() {
                try {
                    String message = get();
                    showPercent(100);
                    showOk(message);
                    store();
                } catch (Exception exception) {
                    showError(messageOf(exception));
                } finally {
                    setBusy(false);
                }
            }
        }.execute();
    }

    private void validateForm() {
        if (sourceField.getText().isBlank()) {
            throw new SeaException("provide a .log file or the .db beside it");
        }
        if (sqlField.getText().isBlank()) {
            throw new SeaException("the .sql file is required");
        }
        requiredPath(sourceField, "provide a .log file or the .db beside it");
        requiredPath(sqlField, "the .sql file is required");
    }

    private void applySettings(DbtranSettings settings) {
        if (settings == null) {
            return;
        }
        sourceField.setText(settings.source());
        sqlField.setText(settings.sql());
    }

    private void close() {
        if (busy) {
            showError("Wait until the current operation finishes.");
            return;
        }
        store();
        dispose();
    }

    private void store() {
        try {
            DbtranSettings.save(DbtranSettings.file(), current());
        } catch (IOException exception) {
            append("could not store the last fields: " + exception.getMessage());
        }
    }

    private DbtranSettings current() {
        return new DbtranSettings(sourceField.getText(), sqlField.getText());
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        run.setEnabled(!busy);
        for (JComponent component : locks) {
            component.setEnabled(!busy);
        }
        setCursor(Cursor.getPredefinedCursor(busy ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    private void showOk(String message) {
        status.setForeground(Look.OK);
        status.setText(message);
        append(message);
    }

    private void showError(String message) {
        status.setForeground(Look.ERROR);
        status.setText(message);
        append(message);
    }

    private void showPercent(int value) {
        int shown = Math.max(0, Math.min(100, value));
        progress.setValue(shown);
        percent.setText(shown + "%");
    }

    private void append(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (transcript.getDocument().getLength() > 0) {
            transcript.append("\n");
        }
        transcript.append(text);
        transcript.setCaretPosition(transcript.getDocument().getLength());
    }

    private void addRow(Component component) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row++;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(0, 0, 10, 0);
        form.add(component, constraints);
    }

    private JPanel labeled(String name, JComponent field, JComponent extra) {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setOpaque(false);
        JLabel label = new JLabel(name);
        label.setPreferredSize(new Dimension(130, 28));
        label.setForeground(Look.INK);
        panel.add(label, BorderLayout.WEST);
        JPanel line = new JPanel(new BorderLayout(8, 0));
        line.setOpaque(false);
        line.add(field, BorderLayout.CENTER);
        line.add(extra, BorderLayout.EAST);
        panel.add(line, BorderLayout.CENTER);
        return panel;
    }

    private JLabel note(String text) {
        JLabel label = new JLabel("<html><div style='width:560px'>" + text + "</div></html>");
        label.setForeground(Look.MUTED);
        return label;
    }

    private void chooseSource() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Log or database");
        chooser.setFileFilter(new FileNameExtensionFilter("SeaDB log or database (*.log, *.db)", "log", "db"));
        pointAt(chooser, sourceField.getText());
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return;
        }
        sourceField.setText(chooser.getSelectedFile().toPath().toString());
    }

    private void chooseSql() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("SQL file");
        chooser.setFileFilter(new FileNameExtensionFilter("SQL script (*.sql)", "sql"));
        pointAt(chooser, sqlField.getText());
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return;
        }
        Path path = chooser.getSelectedFile().toPath();
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".sql")) {
            path = path.resolveSibling(name + ".sql");
        }
        sqlField.setText(path.toString());
    }

    private static void pointAt(JFileChooser chooser, String current) {
        if (current == null || current.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(current.trim());
            Path dir = Files.isDirectory(path) ? path : path.getParent();
            if (dir != null && Files.isDirectory(dir)) {
                chooser.setCurrentDirectory(dir.toFile());
            }
        } catch (InvalidPathException ignored) {
            // the field is not a path yet
        }
    }

    private static void requiredPath(JTextField field, String missing) {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            throw new SeaException(missing);
        }
        try {
            Path.of(text);
        } catch (InvalidPathException exception) {
            throw new SeaException("invalid path: " + text);
        }
    }

    private JTextField field() {
        JTextField field = new JTextField();
        field.setFont(Look.UI);
        locks.add(field);
        return field;
    }

    private JButton browse(Runnable action) {
        JButton button = Look.button("Browse");
        button.addActionListener(event -> action.run());
        locks.add(button);
        return button;
    }

    private static String messageOf(Throwable error) {
        Throwable current = error;
        while ((current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.toString() : message;
    }
}
