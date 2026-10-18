package io.github.gabrielmmoraes1999.nexora.dbsrv.console;

import io.github.gabrielmmoraes1999.nexora.NexException;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * First window of dbeng and dbsrv. It asks for the database, the cache size, and the options.
 * An empty options field starts the database. {@code -a banco.log} applies the log.
 * {@code -x tcpip(port=4580)} changes the port.
 */
public final class StartDialog extends JDialog {

    private static final Color NAVY = new Color(0x1B3A4B);
    private static final Color INK = new Color(0x1C2833);
    private static final Color MUTED = new Color(0x667085);
    private static final Color CHROME = new Color(0xF6F8FB);
    private static final Color LINE = new Color(0xD5DDE6);

    private final Kind kind;
    private final JTextField database = new JTextField(28);
    private final JTextField cache = new JTextField(12);
    private final JTextField options = new JTextField(28);
    private Path directory;
    private Arguments chosen;

    private StartDialog(Kind kind, Arguments preset) {
        super((JDialog) null, kind.product(), true);
        this.kind = kind;
        Font ui = getFont() == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 13) : getFont();
        database.setFont(ui);
        cache.setFont(ui);
        options.setFont(ui);
        cache.setText(preset.cache() == null || preset.cache().isBlank() ? Arguments.DEFAULT_CACHE : preset.cache());
        options.setText(optionsOf(preset));
        if (preset.databases().size() == 1) {
            Path file = preset.databases().get(0).path();
            database.setText(file.toString());
            directory = file.getParent();
        }
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getContentPane().add(header(ui), BorderLayout.NORTH);
        getContentPane().add(form(ui), BorderLayout.CENTER);
        getContentPane().add(buttons(), BorderLayout.SOUTH);
        pack();
        setMinimumSize(new Dimension(Math.max(520, getWidth()), getHeight()));
        setLocationRelativeTo(null);
        setResizable(false);
    }

    /**
     * @return the startup chosen with OK, or null when the window is cancelled
     */
    public static Arguments ask(Kind kind, Arguments preset) {
        StartDialog dialog = new StartDialog(kind, preset);
        dialog.setVisible(true);
        return dialog.chosen;
    }

    private JPanel header(Font ui) {
        JPanel band = new JPanel(new BorderLayout());
        band.setBackground(NAVY);
        band.setBorder(new EmptyBorder(14, 16, 14, 16));
        JLabel title = new JLabel(kind.product());
        title.setForeground(Color.WHITE);
        title.setFont(ui.deriveFont(Font.BOLD, 16f));
        JLabel subtitle = new JLabel("Database, cache size, and options");
        subtitle.setForeground(new Color(0xD6E4EE));
        subtitle.setFont(ui);
        JPanel text = new JPanel(new BorderLayout(0, 2));
        text.setOpaque(false);
        text.add(title, BorderLayout.NORTH);
        text.add(subtitle, BorderLayout.SOUTH);
        band.add(text, BorderLayout.CENTER);
        return band;
    }

    private JPanel form(Font ui) {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(Color.WHITE);
        form.setBorder(new EmptyBorder(16, 16, 8, 16));
        JButton browse = new JButton("Browse...");
        browse.setFont(ui);
        browse.addActionListener(event -> browse());
        addRow(form, 0, "Database", database, browse);
        addRow(form, 1, "Cache size", cache, null);
        addRow(form, 2, "Options", options, null);
        JLabel hint = new JLabel("<html>Leave options empty to start the database.<br>"
                + "-a banco.log applies the log and exits.<br>"
                + "-x tcpip(port=4580) changes the port.</html>");
        hint.setFont(ui);
        hint.setForeground(MUTED);
        GridBagConstraints hintCell = new GridBagConstraints();
        hintCell.gridx = 0;
        hintCell.gridy = 3;
        hintCell.gridwidth = 3;
        hintCell.anchor = GridBagConstraints.WEST;
        hintCell.insets = new Insets(10, 0, 0, 0);
        form.add(hint, hintCell);
        return form;
    }

    private JPanel buttons() {
        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(event -> accept());
        cancel.addActionListener(event -> dispose());
        getRootPane().setDefaultButton(ok);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        bar.setBackground(CHROME);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, LINE),
                new EmptyBorder(10, 12, 10, 12)));
        bar.add(ok);
        bar.add(cancel);
        return bar;
    }

    private void accept() {
        String file = database.getText().trim();
        if (file.isBlank()) {
            warn("Choose a database file.");
            return;
        }
        if (!Files.isRegularFile(Path.of(file))) {
            warn("Database file not found.");
            return;
        }
        List<String> tokens = new ArrayList<>();
        String size = cache.getText().trim();
        if (!size.isBlank()) {
            tokens.add("-c");
            tokens.add(size);
        }
        try {
            tokens.addAll(Options.split(options.getText()));
        } catch (NexException exception) {
            warn(firstLine(exception.getMessage()));
            return;
        }
        tokens.add(file);
        Arguments parsed;
        try {
            parsed = Arguments.parse(kind, tokens.toArray(String[]::new));
        } catch (NexException exception) {
            warn(firstLine(exception.getMessage()));
            return;
        }
        if (parsed.applyLog() != null && !Files.isRegularFile(parsed.applyLog())) {
            warn("Transaction log not found.");
            return;
        }
        chosen = parsed;
        dispose();
    }

    private void browse() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Database");
        chooser.setFileFilter(new FileNameExtensionFilter("Nexora database (*.db)", "db"));
        if (directory != null) {
            chooser.setCurrentDirectory(directory.toFile());
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        database.setText(file.toAbsolutePath().toString());
        if (file.getParent() != null) {
            directory = file.getParent();
        }
    }

    private void warn(String message) {
        JOptionPane.showMessageDialog(this, message, kind.product(), JOptionPane.WARNING_MESSAGE);
    }

    private static void addRow(JPanel form, int row, String name, JTextField field, JButton extra) {
        GridBagConstraints label = new GridBagConstraints();
        label.gridx = 0;
        label.gridy = row;
        label.anchor = GridBagConstraints.WEST;
        label.insets = new Insets(0, 0, 8, 10);
        JLabel caption = new JLabel(name);
        caption.setForeground(INK);
        form.add(caption, label);
        GridBagConstraints value = new GridBagConstraints();
        value.gridx = 1;
        value.gridy = row;
        value.weightx = 1;
        value.fill = GridBagConstraints.HORIZONTAL;
        value.insets = new Insets(0, 0, 8, extra == null ? 0 : 8);
        form.add(field, value);
        if (extra != null) {
            GridBagConstraints button = new GridBagConstraints();
            button.gridx = 2;
            button.gridy = row;
            button.anchor = GridBagConstraints.WEST;
            button.insets = new Insets(0, 0, 8, 0);
            form.add(extra, button);
        }
    }

    static String optionsOf(Arguments preset) {
        StringBuilder text = new StringBuilder();
        if (preset.port() != Arguments.DEFAULT_PORT) {
            text.append("-x tcpip(port=").append(preset.port()).append(')');
        }
        if (preset.host() != null && !preset.host().equals(preset.kind().defaultHost())) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append("--host ").append(preset.host());
        }
        if (preset.applyLog() != null) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append("-a ").append(preset.applyLog().getFileName());
        }
        return text.toString();
    }

    private static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "Could not start the database.";
        }
        int breakAt = message.indexOf('\n');
        return breakAt < 0 ? message : message.substring(0, breakAt);
    }
}
