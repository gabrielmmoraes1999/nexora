package io.github.gabrielmmoraes1999.nexora.dbsrv.console;

import io.github.gabrielmmoraes1999.nexora.Cache;
import io.github.gabrielmmoraes1999.nexora.Database;
import io.github.gabrielmmoraes1999.nexora.NexException;
import io.github.gabrielmmoraes1999.nexora.dbsrv.MotorConfig;
import io.github.gabrielmmoraes1999.nexora.dbsrv.MotorLog;
import io.github.gabrielmmoraes1999.nexora.dbsrv.MotorServer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.BadLocationException;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Taskbar;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The server window: a message console for the personal server and the network server.
 */
public final class ServerConsole extends JFrame {

    private static final Color NAVY = new Color(0x1B3A4B);
    private static final Color ACCENT = new Color(0x1F6AA5);
    private static final Color INK = new Color(0x1C2833);
    private static final Color MUTED = new Color(0x667085);
    private static final Color LINE = new Color(0xD5DDE6);
    private static final Color PAPER = new Color(0xFFFEFB);
    private static final Color CHROME = new Color(0xF6F8FB);
    private static final Color ERROR = new Color(0xB42318);
    private static final Color RUNNING = new Color(0x067647);
    private static final Color RUNNING_WASH = new Color(0xE6F4EA);
    private static final Color STOPPED = new Color(0x667085);
    private static final Color STOPPED_WASH = new Color(0xF2F4F7);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MM/dd HH:mm:ss");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss", Locale.ENGLISH);
    private static final int LOG_LIMIT = 350_000;

    private final Arguments arguments;
    private final String host;
    private final int port;
    private final JTextPane console = new JTextPane();
    private final JButton shutdownButton = new JButton("Shutdown");
    private final JLabel state = new JLabel("Stopped");
    private final JLabel address = new JLabel(" ");
    private final JLabel counts = new JLabel("0 databases · 0 connections");
    private final Object bootMonitor = new Object();
    private final Style accentStyle;
    private final Style mutedStyle;
    private final Style infoStyle;
    private final Style errorStyle;
    private final boolean applyMode;
    private volatile MotorServer server;
    private volatile boolean shuttingDown;
    private TrayIcon trayIcon;
    private volatile boolean logSql;
    private boolean bootFinished;
    private boolean announced;
    private Path lastDirectory;

    private final MotorLog log = new MotorLog() {
        @Override
        public void databaseStarted(String name, Path file, int pageSize) {
            String when = WHEN.format(LocalDateTime.now());
            info("Starting database \"" + name + "\" (" + file.toAbsolutePath() + ") at " + when);
            info("Transaction log: " + logBeside(file).toAbsolutePath());
            info("Page size: " + pageSize + " bytes");
            long adjusted = Cache.adjust(arguments.cacheBytes(), pageSize);
            info("Cache size adjusted to " + kib(adjusted));
            info("Maximum cache size: " + kib(adjusted));
            info("Database \"" + name + "\" (" + file.getFileName() + ") started at " + when);
            info("Clients use jdbc:nexora://" + clientHost() + ":" + listeningPort() + "/" + name);
            refresh();
        }

        @Override
        public void connected(long id, String database, String user, String from) {
            info("Connection " + id + ": \"" + user + "\" connected to database \"" + database + "\" from " + from);
            refresh();
        }

        @Override
        public void disconnected(long id, String database, String user, String from) {
            info("Disconnect " + id + ": \"" + user + "\" disconnected from database \"" + database + "\"");
            refresh();
        }

        @Override
        public void statement(String database, String user, String sql) {
            if (!logSql || sql == null || sql.isBlank()) {
                return;
            }
            info("SQL [" + database + "] " + user + ": " + oneLine(sql));
        }

        @Override
        public void failed(String message) {
            if (message != null && !message.isBlank()) {
                error(message);
            }
        }
    };

    private ServerConsole(Arguments arguments) {
        super(arguments.kind().product());
        this.arguments = arguments;
        this.host = arguments.host();
        this.port = arguments.port();
        this.applyMode = arguments.applyLog() != null;
        Font ui = font("Segoe UI", Font.PLAIN, 13);
        Font mono = font("Consolas", Font.PLAIN, 13);
        setFont(ui);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setIconImages(iconImages());
        installTaskbarIcon();
        console.setFont(mono);
        console.setEditable(false);
        console.setBackground(PAPER);
        console.setForeground(INK);
        console.setMargin(new Insets(10, 12, 10, 12));
        console.setCaretColor(ACCENT);
        accentStyle = style("accent", mono, ACCENT);
        mutedStyle = style("muted", mono, MUTED);
        infoStyle = style("info", mono, INK);
        errorStyle = style("error", mono, ERROR);
        JPopupMenu popup = new JPopupMenu();
        popup.add(item("Copy", console::copy));
        popup.add(item("Select all", console::selectAll));
        popup.add(item("Clear console", this::clear));
        console.setComponentPopupMenu(popup);
        setJMenuBar(menu());
        add(header(ui), BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(console,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(PAPER);
        add(scroll, BorderLayout.CENTER);
        add(statusBar(ui), BorderLayout.SOUTH);
        shutdownButton.setFont(ui.deriveFont(Font.BOLD));
        shutdownButton.addActionListener(event -> shutdown(true));
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (canHide()) {
                    hideToTray();
                } else {
                    shutdown(false);
                }
            }

            @Override
            public void windowIconified(WindowEvent event) {
                if (canHide()) {
                    hideToTray();
                }
            }
        });
        setSize(860, 540);
        setMinimumSize(new Dimension(640, 420));
        setLocationRelativeTo(null);
        Thread.ofPlatform().name("nex-boot").start(this::boot);
    }

    public static void open(Arguments arguments) {
        if (arguments == null) {
            throw new NexException("server arguments are required");
        }
        installLook();
        SwingUtilities.invokeLater(() -> new ServerConsole(arguments).setVisible(true));
    }

    static void installLook() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // the window keeps the look Java already chose
        }
    }

    private void boot() {
        try {
            banner();
            if (shuttingDown) {
                return;
            }
            if (applyMode) {
                applyAndFinish();
                return;
            }
            if (arguments.databases().isEmpty()) {
                server = MotorServer.listen(host, port, log, arguments.cacheBytes());
            } else {
                server = MotorServer.start(new MotorConfig(host, port, arguments.databases()), log, arguments.cacheBytes());
            }
            if (shuttingDown) {
                server.close();
                server = null;
                return;
            }
            announce();
            installTray();
            if (arguments.databases().isEmpty()) {
                info("No database is started. Choose File, then Start database.");
            }
        } catch (RuntimeException exception) {
            server = null;
            error(message(exception));
            error("The server is not accepting requests.");
            refresh();
        } finally {
            synchronized (bootMonitor) {
                bootFinished = true;
                bootMonitor.notifyAll();
            }
        }
    }

    private void banner() {
        info(arguments.kind().product() + " Version 1.0");
        info("");
        info("Running " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " on " + System.getProperty("os.arch"));
        info("Java " + System.getProperty("java.version"));
        info("Processors detected: " + Runtime.getRuntime().availableProcessors());
        info("");
        if (arguments.kind() == Kind.PERSONAL) {
            info("This server accepts connections from this machine.");
        } else {
            info("This server accepts connections from the network.");
        }
        info("Cache size: " + arguments.cache());
        info("");
    }

    private void applyAndFinish() {
        Path file = arguments.databases().get(0).path();
        Path transactionLog = arguments.applyLog();
        info("Applying transaction log " + transactionLog);
        info("Database: " + file.toAbsolutePath());
        try {
            Database.applyLog(file, transactionLog, Cache.of(arguments.cacheBytes()));
        } catch (RuntimeException exception) {
            error(message(exception));
            error("The log was not applied. Shutdown closes this window.");
            return;
        }
        info("Transaction log applied.");
        info("Server shutting down");
        try {
            Thread.sleep(1200);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        SwingUtilities.invokeLater(() -> {
            removeTray();
            dispose();
            System.exit(0);
        });
    }

    private void announce() {
        if (announced) {
            return;
        }
        announced = true;
        MotorServer current = server;
        int listening = current == null ? port : current.port();
        info("Trying to start TCPIP link ...");
        if ("0.0.0.0".equals(host) || "::".equals(host)) {
            info("Starting on port " + listening);
        } else {
            info("Starting on " + host + " port " + listening);
        }
        info("    TCPIP link started successfully");
        if (current != null && current.databaseCount() == 0) {
            info("Maximum cache size: " + kib(current.cacheMaximum()));
        }
        info("Now accepting requests");
        refresh();
    }

    private void startDatabase() {
        Picked picked = askDatabase();
        if (picked == null || shuttingDown) {
            return;
        }
        Thread.ofPlatform().name("nex-publish").start(() -> publish(picked));
    }

    private void publish(Picked picked) {
        awaitBoot();
        if (shuttingDown) {
            return;
        }
        try {
            if (server == null) {
                server = MotorServer.listen(host, port, log, arguments.cacheBytes());
                if (shuttingDown) {
                    server.close();
                    server = null;
                    return;
                }
                announce();
                installTray();
            }
            server.publish(picked.name(), picked.path());
        } catch (RuntimeException exception) {
            error(message(exception));
        }
    }

    private void shutdown(boolean ask) {
        if (shuttingDown) {
            return;
        }
        if (ask && server != null) {
            int answer = JOptionPane.showConfirmDialog(
                    this,
                    "Shut down " + arguments.kind().product() + "?",
                    "Shut down",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
        }
        shuttingDown = true;
        shutdownButton.setEnabled(false);
        info("Server shutting down");
        Thread.ofPlatform().name("nex-shutdown").start(() -> {
            awaitBoot();
            MotorServer current = server;
            if (current != null) {
                current.close();
            }
            SwingUtilities.invokeLater(() -> {
                removeTray();
                dispose();
                System.exit(0);
            });
        });
    }

    private boolean canHide() {
        return trayIcon != null && server != null && !applyMode && !shuttingDown;
    }

    private void hideToTray() {
        setVisible(false);
        if (trayIcon != null) {
            trayIcon.displayMessage(
                    arguments.kind().product(),
                    "Running in the background.",
                    TrayIcon.MessageType.INFO);
        }
    }

    private void showFromTray() {
        setExtendedState(Frame.NORMAL);
        setVisible(true);
        toFront();
        requestFocus();
    }

    private void installTray() {
        if (applyMode || !SystemTray.isSupported()) {
            return;
        }
        Runnable job = () -> {
            if (trayIcon != null || shuttingDown) {
                return;
            }
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Open");
            open.addActionListener(event -> SwingUtilities.invokeLater(this::showFromTray));
            MenuItem shut = new MenuItem("Shutdown");
            shut.addActionListener(event -> SwingUtilities.invokeLater(() -> shutdown(true)));
            menu.add(open);
            menu.addSeparator();
            menu.add(shut);
            TrayIcon icon = new TrayIcon(icon(16), arguments.kind().product(), menu);
            icon.setImageAutoSize(true);
            icon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (event.getClickCount() == 2) {
                        SwingUtilities.invokeLater(ServerConsole.this::showFromTray);
                    }
                }
            });
            try {
                SystemTray.getSystemTray().add(icon);
                trayIcon = icon;
            } catch (AWTException ignored) {
                // without a tray icon, closing the window shuts the server down
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            job.run();
        } else {
            SwingUtilities.invokeLater(job);
        }
    }

    private void removeTray() {
        TrayIcon icon = trayIcon;
        trayIcon = null;
        if (icon != null && SystemTray.isSupported()) {
            SystemTray.getSystemTray().remove(icon);
        }
    }

    private Picked askDatabase() {
        JDialog dialog = new JDialog(this, "Start database", true);
        JTextField pathField = new JTextField(28);
        JTextField nameField = new JTextField(28);
        pathField.setFont(getFont());
        nameField.setFont(getFont());
        JButton browse = new JButton("Browse...");
        browse.addActionListener(event -> browse(dialog, pathField, nameField));
        JButton start = new JButton("Start");
        JButton cancel = new JButton("Cancel");
        final Picked[] chosen = new Picked[1];
        start.addActionListener(event -> {
            String pathText = pathField.getText().trim();
            String name = nameField.getText().trim();
            if (pathText.isBlank()) {
                JOptionPane.showMessageDialog(dialog, "Choose a .db file.", "Start database", JOptionPane.WARNING_MESSAGE);
                return;
            }
            Path path = Path.of(pathText);
            if (!Files.isRegularFile(path)) {
                JOptionPane.showMessageDialog(dialog, "Database file not found.", "Start database", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (name.isBlank()) {
                name = Arguments.stem(path);
            }
            if (name.isBlank()) {
                JOptionPane.showMessageDialog(dialog, "Database name is required.", "Start database", JOptionPane.WARNING_MESSAGE);
                return;
            }
            chosen[0] = new Picked(name, path);
            dialog.dispose();
        });
        cancel.addActionListener(event -> dialog.dispose());
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(16, 16, 8, 16));
        form.setBackground(Color.WHITE);
        GridBagConstraints label = constraints(0, 0);
        label.insets = new Insets(0, 0, 8, 8);
        form.add(new JLabel("Database file"), label);
        GridBagConstraints field = constraints(1, 0);
        field.fill = GridBagConstraints.HORIZONTAL;
        field.weightx = 1;
        field.insets = new Insets(0, 0, 8, 8);
        form.add(pathField, field);
        GridBagConstraints browseCell = constraints(2, 0);
        browseCell.insets = new Insets(0, 0, 8, 0);
        form.add(browse, browseCell);
        GridBagConstraints nameLabel = constraints(0, 1);
        nameLabel.insets = new Insets(0, 0, 0, 8);
        form.add(new JLabel("Database name"), nameLabel);
        GridBagConstraints nameCell = constraints(1, 1);
        nameCell.gridwidth = 2;
        nameCell.fill = GridBagConstraints.HORIZONTAL;
        nameCell.weightx = 1;
        form.add(nameField, nameCell);
        JLabel hint = new JLabel("The name is the one after the port in jdbc:nexora://host:port/name");
        hint.setForeground(MUTED);
        hint.setBorder(new EmptyBorder(8, 16, 0, 16));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setBackground(CHROME);
        buttons.setBorder(new EmptyBorder(10, 12, 10, 12));
        buttons.add(cancel);
        buttons.add(start);
        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(Color.WHITE);
        body.add(form, BorderLayout.CENTER);
        body.add(hint, BorderLayout.SOUTH);
        dialog.getContentPane().add(body, BorderLayout.CENTER);
        dialog.getContentPane().add(buttons, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(start);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(520, dialog.getHeight()));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
        return chosen[0];
    }

    private void browse(JDialog parent, JTextField pathField, JTextField nameField) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Start database");
        chooser.setFileFilter(new FileNameExtensionFilter("Nexora database (*.db)", "db"));
        if (lastDirectory != null) {
            chooser.setCurrentDirectory(lastDirectory.toFile());
        }
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        String previous = Arguments.stem(pathField.getText().isBlank() ? file : Path.of(pathField.getText().trim()));
        pathField.setText(file.toAbsolutePath().toString());
        if (nameField.getText().isBlank() || nameField.getText().trim().equals(previous)) {
            nameField.setText(Arguments.stem(file));
        }
        if (file.getParent() != null) {
            lastDirectory = file.getParent();
        }
    }

    private JMenuBar menu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        file.setMnemonic(KeyEvent.VK_F);
        JMenuItem start = item("Start database...", this::startDatabase);
        start.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        JMenuItem shut = item("Shut down", () -> shutdown(true));
        file.add(start);
        file.addSeparator();
        file.add(shut);
        JMenu edit = new JMenu("Edit");
        edit.setMnemonic(KeyEvent.VK_E);
        edit.add(item("Copy", console::copy));
        edit.add(item("Select all", console::selectAll));
        edit.add(item("Clear console", this::clear));
        JMenu serverMenu = new JMenu("Server");
        serverMenu.setMnemonic(KeyEvent.VK_S);
        JCheckBoxMenuItem sql = new JCheckBoxMenuItem("Log SQL");
        sql.addActionListener(event -> {
            logSql = sql.isSelected();
            info(logSql ? "SQL logging is on." : "SQL logging is off.");
        });
        serverMenu.add(sql);
        bar.add(file);
        bar.add(edit);
        bar.add(serverMenu);
        return bar;
    }

    private JComponent header(Font ui) {
        JPanel band = new JPanel(new BorderLayout(12, 0));
        band.setBackground(NAVY);
        band.setBorder(new EmptyBorder(12, 16, 12, 16));
        JLabel mark = new JLabel(new javax.swing.ImageIcon(icon(28)));
        JLabel title = new JLabel(arguments.kind().product());
        title.setForeground(Color.WHITE);
        title.setFont(ui.deriveFont(Font.BOLD, 16f));
        state.setOpaque(true);
        state.setFont(ui.deriveFont(Font.BOLD, 12f));
        state.setBorder(new EmptyBorder(4, 10, 4, 10));
        paintState(false);
        JPanel pill = new JPanel(new java.awt.GridBagLayout());
        pill.setOpaque(false);
        pill.add(state);
        band.add(mark, BorderLayout.WEST);
        band.add(title, BorderLayout.CENTER);
        band.add(pill, BorderLayout.EAST);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(band, BorderLayout.CENTER);
        JPanel stripe = new JPanel();
        stripe.setBackground(ACCENT);
        stripe.setPreferredSize(new Dimension(0, 3));
        wrap.add(stripe, BorderLayout.SOUTH);
        return wrap;
    }

    private JComponent statusBar(Font ui) {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(CHROME);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, LINE),
                new EmptyBorder(6, 12, 6, 12)));
        address.setFont(ui);
        counts.setFont(ui);
        address.setForeground(INK);
        counts.setForeground(MUTED);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        left.setOpaque(false);
        left.add(shutdownButton);
        left.add(address);
        bar.add(left, BorderLayout.WEST);
        bar.add(counts, BorderLayout.EAST);
        return bar;
    }

    private void refresh() {
        Runnable job = () -> {
            MotorServer current = server;
            boolean up = current != null && announced && !shuttingDown;
            paintState(up);
            if (current == null) {
                address.setText("Not listening");
                counts.setText("0 databases · 0 connections");
                return;
            }
            int listening = current.port();
            if ("0.0.0.0".equals(host) || "::".equals(host)) {
                address.setText("port " + listening);
            } else {
                address.setText(host + ":" + listening);
            }
            int databases = current.databaseCount();
            int connections = current.connections();
            counts.setText(count(databases, "database") + " · " + count(connections, "connection"));
        };
        if (SwingUtilities.isEventDispatchThread()) {
            job.run();
        } else {
            SwingUtilities.invokeLater(job);
        }
    }

    private void paintState(boolean up) {
        state.setText(up ? "Running" : "Stopped");
        state.setForeground(up ? RUNNING : STOPPED);
        state.setBackground(up ? RUNNING_WASH : STOPPED_WASH);
    }

    private void info(String message) {
        write(false, message);
    }

    private void error(String message) {
        write(true, message);
    }

    private void write(boolean failure, String message) {
        String text = message == null ? "" : message;
        Runnable job = () -> {
            StyledDocument document = console.getStyledDocument();
            try {
                trim(document);
                if (text.isEmpty()) {
                    document.insertString(document.getLength(), "\n", infoStyle);
                } else if (failure) {
                    document.insertString(document.getLength(), "E. " + STAMP.format(LocalDateTime.now()) + ". " + text + "\n", errorStyle);
                } else {
                    document.insertString(document.getLength(), "I. ", accentStyle);
                    document.insertString(document.getLength(), STAMP.format(LocalDateTime.now()) + ". ", mutedStyle);
                    document.insertString(document.getLength(), text + "\n", infoStyle);
                }
                console.setCaretPosition(document.getLength());
            } catch (BadLocationException ignored) {
                // the console keeps the lines already written
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            job.run();
        } else {
            SwingUtilities.invokeLater(job);
        }
    }

    private void trim(StyledDocument document) throws BadLocationException {
        if (document.getLength() <= LOG_LIMIT) {
            return;
        }
        int extra = document.getLength() - (LOG_LIMIT / 2);
        int breakAt = document.getText(extra, Math.min(2000, document.getLength() - extra)).indexOf('\n');
        document.remove(0, breakAt < 0 ? extra : extra + breakAt + 1);
    }

    private void clear() {
        Runnable job = () -> console.setText("");
        if (SwingUtilities.isEventDispatchThread()) {
            job.run();
        } else {
            SwingUtilities.invokeLater(job);
        }
    }

    private void awaitBoot() {
        synchronized (bootMonitor) {
            while (!bootFinished) {
                try {
                    bootMonitor.wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private int listeningPort() {
        MotorServer current = server;
        return current == null ? port : current.port();
    }

    private String clientHost() {
        if ("0.0.0.0".equals(host) || "::".equals(host)) {
            return "<host>";
        }
        return host;
    }

    private Style style(String name, Font font, Color color) {
        Style style = console.addStyle(name, null);
        StyleConstants.setFontFamily(style, font.getFamily());
        StyleConstants.setFontSize(style, font.getSize());
        StyleConstants.setForeground(style, color);
        return style;
    }

    private static JMenuItem item(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(event -> action.run());
        return item;
    }

    private static GridBagConstraints constraints(int x, int y) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = x;
        constraints.gridy = y;
        constraints.anchor = GridBagConstraints.WEST;
        return constraints;
    }

    private static String count(int value, String noun) {
        return value + " " + noun + (value == 1 ? "" : "s");
    }

    private static String oneLine(String sql) {
        String flat = sql.replace('\r', ' ').replace('\n', ' ').replaceAll(" +", " ").trim();
        if (flat.length() > 180) {
            return flat.substring(0, 177) + "...";
        }
        return flat;
    }

    private static Path logBeside(Path file) {
        String name = Arguments.stem(file) + ".log";
        return file.getParent() == null ? Path.of(name) : file.getParent().resolve(name);
    }

    private static String kib(long bytes) {
        if (bytes % 1024 == 0) {
            return (bytes / 1024) + "K";
        }
        return bytes + " bytes";
    }

    private static String message(Throwable exception) {
        String text = exception.getMessage();
        if (text == null || text.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        int breakAt = text.indexOf('\n');
        return breakAt < 0 ? text : text.substring(0, breakAt);
    }

    private void installTaskbarIcon() {
        try {
            if (Taskbar.isTaskbarSupported()) {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.setIconImage(icon(64));
                }
            }
        } catch (UnsupportedOperationException ignored) {
            // the window keeps the icon set on the frame
        }
    }

    private static Font font(String name, int style, int size) {
        Font candidate = new Font(name, style, size);
        if (name.equalsIgnoreCase(candidate.getFamily())) {
            return candidate;
        }
        if ("Consolas".equals(name)) {
            return new Font(Font.MONOSPACED, style, size);
        }
        return new Font(Font.SANS_SERIF, style, size);
    }

    private static List<Image> iconImages() {
        List<Image> images = new ArrayList<>();
        for (int size : new int[]{16, 20, 24, 32, 40, 48, 64, 128, 256}) {
            images.add(icon(size));
        }
        return List.copyOf(images);
    }

    private static BufferedImage icon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float side = size;
        g.setPaint(new GradientPaint(0, 0, new Color(0x2F86C5), 0, side, new Color(0x143044)));
        g.fill(new RoundRectangle2D.Float(0, 0, side, side, side * 0.28f, side * 0.28f));
        float left = side * 0.22f;
        float width = side * 0.56f;
        float top = side * 0.24f;
        float ellipse = Math.max(3f, side * 0.16f);
        float body = side * 0.46f;
        g.setColor(Color.WHITE);
        g.fill(new Rectangle2D.Float(left, top + ellipse / 2f, width, body));
        g.fill(new Ellipse2D.Float(left, top + body - ellipse / 2f, width, ellipse));
        g.setColor(new Color(0xE7F3FB));
        g.fill(new Ellipse2D.Float(left, top, width, ellipse));
        g.dispose();
        return image;
    }

    private record Picked(String name, Path path) {
    }
}
