package io.github.gabrielmmoraes1999.nexora.backup;

import io.github.gabrielmmoraes1999.nexora.NexException;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
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
import java.awt.event.ItemEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.IntConsumer;

/**
 * Window for a full backup and an incremental backup.
 * The fields match the CLI: a {@code .db} path or a JDBC URL, then the directory.
 */
final class BackupFrame extends JFrame {

    private enum Operation {
        FULL, INCREMENTAL
    }

    private final List<JComponent> locks = new ArrayList<>();
    private final ButtonGroup operations = new ButtonGroup();
    private final JPanel form = new JPanel(new GridBagLayout());
    private final JLabel hint = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
    private final JLabel percent = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JTextArea transcript = new JTextArea(7, 40);
    private final JButton run = Look.primary("Backup");

    private final JRadioButton fullOption;
    private final JRadioButton incrementalOption;

    private final JTextField sourceField = field();
    private final JTextField userField = field();
    private final JPasswordField passwordField = password();
    private final JTextField roleField = field();
    private final JCheckBox rememberPassword = check("Remember password");
    private final JTextField directoryField = field();

    private final JButton sourceBrowse = browse(() -> choose(sourceField, "Nexora database (*.db)", "db"));
    private final JButton directoryBrowse = browse(this::chooseDirectory);

    private int row;
    private boolean busy;

    BackupFrame() {
        super("Nexora Backup");
        fullOption = radio("Full backup");
        incrementalOption = radio("Incremental");
        hint.setForeground(Look.MUTED);
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
        applySettings(BackupSettings.load(BackupSettings.file()));
        if (operations.getSelection() == null) {
            fullOption.setSelected(true);
        }
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                close();
            }
        });
        pack();
        setSize(Math.max(getWidth(), 760), Math.max(getHeight(), 640));
        setMinimumSize(new Dimension(680, 560));
        setLocationRelativeTo(null);
    }

    static void report(Component parent, Throwable error) {
        JOptionPane.showMessageDialog(parent, messageOf(error), "Nexora Backup", JOptionPane.ERROR_MESSAGE);
    }

    private JPanel header() {
        JPanel header = new JPanel();
        header.setLayout(new javax.swing.BoxLayout(header, javax.swing.BoxLayout.Y_AXIS));
        header.setOpaque(true);
        header.setBackground(Look.CHROME);
        header.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(0, 0, 1, 0, Look.LINE),
                new EmptyBorder(16, 20, 12, 20)));
        JLabel title = new JLabel("Nexora Backup");
        title.setFont(Look.UI.deriveFont(Font.BOLD, 20f));
        title.setForeground(Look.NAVY);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel subtitle = new JLabel("Full backup and incremental backup.");
        subtitle.setForeground(Look.MUTED);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 0));
        options.setOpaque(false);
        options.setAlignmentX(Component.LEFT_ALIGNMENT);
        options.add(fullOption);
        options.add(incrementalOption);
        header.add(title);
        header.add(Box.createVerticalStrut(2));
        header.add(subtitle);
        header.add(Box.createVerticalStrut(10));
        header.add(options);
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
        hint.setText("<html><div style='width:560px'>" + hintText() + "</div></html>");
        addRow(hint);
        addRow(labeled("Database or URL", sourceField, sourceBrowse));
        addRow(note("A JDBC URL needs a user and a password, and that user must be a DBA. For a local file, leave them blank."));
        addRow(labeled("User", userField, null));
        addRow(labeled("Password", passwordField, rememberPassword));
        addRow(labeled("Role", roleField, null));
        addRow(labeled("Directory", directoryField, directoryBrowse));
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
        } catch (NexException exception) {
            showError(exception.getMessage());
            return;
        }
        Operation operation = operation();
        boolean incremental = operation == Operation.INCREMENTAL;
        String source = sourceField.getText().trim();
        boolean credentials = needsCredentials();
        String user = credentials ? userField.getText().trim() : null;
        String password = credentials ? new String(passwordField.getPassword()) : null;
        String role = credentials ? roleField.getText().trim() : null;
        Path directory = pathOrNull(directoryField);
        setBusy(true);
        showPercent(0);
        status.setForeground(Look.MUTED);
        status.setText("Working...");
        append(started(operation));
        new SwingWorker<String, Integer>() {
            @Override
            protected String doInBackground() {
                IntConsumer callback = this::publishPercent;
                return BackupService.backup(source, user, password, role, incremental, directory, callback);
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
            throw new NexException("provide the .db file");
        }
        if (directoryField.getText().isBlank()) {
            throw new NexException("backup directory is required");
        }
        requiredPath(directoryField, "backup directory is required");
        if (!BackupService.connection(sourceField.getText())) {
            requiredPath(sourceField, "provide the .db file");
        }
        if (needsCredentials()
                && (userField.getText().isBlank() || passwordField.getPassword().length == 0)) {
            throw new NexException("user and password are required");
        }
    }

    private boolean needsCredentials() {
        return BackupService.connection(sourceField.getText())
                || !userField.getText().isBlank()
                || passwordField.getPassword().length > 0
                || !roleField.getText().isBlank();
    }

    private String started(Operation operation) {
        return switch (operation) {
            case FULL -> "Full backup of " + sourceField.getText().trim();
            case INCREMENTAL -> "Incremental backup of " + sourceField.getText().trim();
        };
    }

    private String hintText() {
        return switch (operation()) {
            case FULL -> "Copies the .db and the .log into the directory. The live log then records only what changes after that copy.";
            case INCREMENTAL -> "Copies only the .log. The live log stays in place.";
        };
    }

    private Operation operation() {
        if (incrementalOption.isSelected()) {
            return Operation.INCREMENTAL;
        }
        return Operation.FULL;
    }

    private void applySettings(BackupSettings settings) {
        if (settings == null) {
            fullOption.setSelected(true);
            return;
        }
        sourceField.setText(settings.source());
        userField.setText(settings.user());
        passwordField.setText(settings.password());
        rememberPassword.setSelected(settings.remember());
        roleField.setText(settings.role());
        directoryField.setText(settings.directory());
        switch (settings.operation() == null ? "" : settings.operation()) {
            case "incremental" -> incrementalOption.setSelected(true);
            default -> fullOption.setSelected(true);
        }
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
            BackupSettings.save(BackupSettings.file(), current());
        } catch (IOException exception) {
            append("could not store the last fields: " + exception.getMessage());
        }
    }

    private BackupSettings current() {
        String operation = switch (operation()) {
            case FULL -> "full";
            case INCREMENTAL -> "incremental";
        };
        return new BackupSettings(
                operation,
                sourceField.getText(),
                userField.getText(),
                new String(passwordField.getPassword()),
                rememberPassword.isSelected(),
                roleField.getText(),
                directoryField.getText());
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
        if (extra == null) {
            panel.add(field, BorderLayout.CENTER);
        } else {
            JPanel line = new JPanel(new BorderLayout(8, 0));
            line.setOpaque(false);
            line.add(field, BorderLayout.CENTER);
            line.add(extra, BorderLayout.EAST);
            panel.add(line, BorderLayout.CENTER);
        }
        return panel;
    }

    private JLabel note(String text) {
        JLabel label = new JLabel("<html><div style='width:560px'>" + text + "</div></html>");
        label.setForeground(Look.MUTED);
        return label;
    }

    private void chooseDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Backup directory");
        pointAt(chooser, directoryField.getText());
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return;
        }
        directoryField.setText(chooser.getSelectedFile().getAbsolutePath());
    }

    private void choose(JTextField field, String description, String extension) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter(description, extension));
        pointAt(chooser, field.getText());
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return;
        }
        field.setText(chooser.getSelectedFile().toPath().toString());
    }

    private static void pointAt(JFileChooser chooser, String current) {
        if (current == null || current.isBlank() || BackupService.connection(current)) {
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

    private static Path pathOrNull(JTextField field) {
        String text = field.getText().trim();
        if (text.isEmpty() || BackupService.connection(text)) {
            return null;
        }
        try {
            return Path.of(text);
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    private static void requiredPath(JTextField field, String missing) {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            throw new NexException(missing);
        }
        try {
            Path.of(text);
        } catch (InvalidPathException exception) {
            throw new NexException("invalid path: " + text);
        }
    }

    private JRadioButton radio(String text) {
        JRadioButton button = new JRadioButton(text);
        button.setOpaque(false);
        button.setFont(Look.UI);
        button.setForeground(Look.INK);
        button.addItemListener(event -> {
            if (event.getStateChange() == ItemEvent.SELECTED) {
                rebuild();
            }
        });
        operations.add(button);
        locks.add(button);
        return button;
    }

    private JTextField field() {
        JTextField field = new JTextField();
        field.setFont(Look.UI);
        locks.add(field);
        return field;
    }

    private JPasswordField password() {
        JPasswordField field = new JPasswordField();
        field.setFont(Look.UI);
        locks.add(field);
        return field;
    }

    private JCheckBox check(String text) {
        JCheckBox box = new JCheckBox(text);
        box.setOpaque(false);
        box.setFont(Look.UI);
        box.setForeground(Look.INK);
        locks.add(box);
        return box;
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
