package io.github.gabrielmmoraes1999.nexora.studio;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

final class StudioFrame extends JFrame implements ExplorerPane.Listener, Hub {

    private final Path registryFile;
    private final List<DatabaseEntry> entries = new ArrayList<>();
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final ExplorerPane explorer = new ExplorerPane(this);
    private final JTabbedPane workspace = new JTabbedPane();
    private final JCheckBox autoCommit = new JCheckBox("Auto-commit", true);
    private final JComboBox<String> schemas = new JComboBox<>();
    private final JLabel statusConnection = statusLabel("sem banco");
    private final JLabel statusUser = statusLabel("");
    private final JLabel statusSchema = statusLabel("");
    private final JLabel statusMode = statusLabel("");
    private final JLabel statusMessage = statusLabel("");
    private final JButton connectButton = Look.button("Conectar");
    private final JButton disconnectButton = Look.button("Desconectar");
    private final JButton refreshButton = Look.button("Atualizar");
    private final JButton queryButton = Look.button("Nova consulta");
    private final JButton runButton = Look.primary("Executar");
    private final JButton commitButton = Look.button("Commit");
    private final JButton rollbackButton = Look.button("Rollback");
    private boolean updatingSchema;
    private int queryCount;

    StudioFrame() {
        super("Nexora Studio");
        registryFile = Registry.defaultFile();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setIconImage(Look.iconImage());
        try {
            entries.addAll(Registry.load(registryFile));
        } catch (Exception exception) {
            Dialogs.error(this, "Não foi possível ler o registro de bancos: " + Dialogs.message(exception));
        }
        setJMenuBar(menu());
        add(toolbar(), BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, explorer, workspace);
        split.setDividerLocation(300);
        split.setResizeWeight(0);
        split.setDividerSize(6);
        split.setBorder(new EmptyBorder(0, 0, 0, 0));
        add(split, BorderLayout.CENTER);
        add(statusBar(), BorderLayout.SOUTH);
        workspace.addChangeListener(event -> updateActions());
        bindKeys();
        addTab("Início", new StartPage(this::createDatabase, this::register, this::newQueryFromStart), false);
        explorer.show(entries, outlines(), "");
        updateActions();
        setSize(1280, 800);
        setMinimumSize(new Dimension(960, 640));
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                exit();
            }
        });
    }

    @Override
    public Component owner() {
        return this;
    }

    @Override
    public Connection connection(String databaseId) {
        Session session = sessions.get(databaseId);
        return session == null ? null : session.connection;
    }

    @Override
    public String currentSchema(String databaseId) {
        Connection connection = connection(databaseId);
        if (connection == null) {
            return "public";
        }
        try {
            String schema = connection.getSchema();
            return schema == null || schema.isBlank() ? "public" : schema;
        } catch (SQLException exception) {
            return "public";
        }
    }

    @Override
    public void refreshOutline(String databaseId) {
        Session session = sessions.get(databaseId);
        if (session == null) {
            explorer.show(entries, outlines(), databaseId);
            return;
        }
        try {
            session.outline = Outline.load(session.connection);
            if (!session.outline.warnings().isEmpty()) {
                message(String.join(" ", session.outline.warnings()));
            }
        } catch (SQLException exception) {
            Dialogs.error(this, Dialogs.message(exception));
        }
        explorer.show(entries, outlines(), databaseId);
        updateActions();
    }

    @Override
    public void openSql(String databaseId, String title, String sql) {
        if (connection(databaseId) == null) {
            DatabaseEntry entry = entry(databaseId);
            if (entry == null || !connectEntry(entry)) {
                return;
            }
        }
        queryCount++;
        String tab = title == null || title.isBlank() || "SQL".equals(title) ? "SQL " + queryCount : title;
        addTab(tab, new QueryPane(this, databaseId, sql), true);
    }

    @Override
    public void openNode(Node node, boolean data) {
        if (node.kind() == Node.Kind.USER) {
            node = Node.folder(node.databaseId(), Node.USERS, "Usuários");
        }
        for (int i = 0; i < workspace.getTabCount(); i++) {
            if (workspace.getComponentAt(i) instanceof ObjectPane pane && pane.same(node)) {
                workspace.setSelectedIndex(i);
                if (data) {
                    pane.showData();
                }
                return;
            }
        }
        if (connection(node.databaseId()) == null) {
            message("conecte o banco antes de abrir o objeto");
            return;
        }
        ObjectPane pane = new ObjectPane(this, node, data);
        addTab(node.title().isBlank() ? node.name() : node.title(), pane, true);
    }

    @Override
    public void message(String text) {
        statusMessage.setText(text == null ? "" : text);
    }

    @Override
    public void register() {
        DatabaseEntry entry = Dialogs.registration(this, null);
        if (entry == null) {
            return;
        }
        entries.add(entry);
        save();
        explorer.show(entries, outlines(), entry.id());
        updateActions();
    }

    @Override
    public void createDatabase() {
        Dialogs.CreatedDatabase created = Dialogs.createDatabase(this);
        if (created == null) {
            return;
        }
        entries.add(created.entry());
        save();
        explorer.show(entries, outlines(), created.entry().id());
        message("banco criado: " + created.entry().path());
        if (created.connect()) {
            connectEntry(created.entry());
        }
        updateActions();
    }

    @Override
    public void connect(Node node) {
        DatabaseEntry entry = entry(node.databaseId());
        if (entry != null) {
            connectEntry(entry);
        }
    }

    @Override
    public void disconnect(Node node) {
        Session session = sessions.get(node.databaseId());
        if (session == null) {
            return;
        }
        try {
            if (!session.connection.getAutoCommit()
                    && !Dialogs.confirm(this, "Desconectar? A transação aberta será desfeita.")) {
                return;
            }
            session.connection.close();
        } catch (SQLException exception) {
            Dialogs.error(this, Dialogs.message(exception));
        }
        sessions.remove(node.databaseId());
        explorer.show(entries, outlines(), node.databaseId());
        updateActions();
    }

    @Override
    public void edit(Node node) {
        DatabaseEntry current = entry(node.databaseId());
        if (current == null) {
            return;
        }
        DatabaseEntry edited = Dialogs.registration(this, current);
        if (edited == null) {
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(edited.id())) {
                entries.set(i, edited);
            }
        }
        save();
        explorer.show(entries, outlines(), edited.id());
    }

    @Override
    public void remove(Node node) {
        DatabaseEntry entry = entry(node.databaseId());
        if (entry == null) {
            return;
        }
        if (!Dialogs.confirm(this, "Remover " + entry.label() + " do registro? O arquivo não é apagado.")) {
            return;
        }
        if (sessions.containsKey(entry.id())) {
            disconnect(node);
        }
        entries.removeIf(item -> item.id().equals(entry.id()));
        save();
        explorer.show(entries, outlines(), "");
        updateActions();
    }

    @Override
    public void refresh(Node node) {
        if (node != null) {
            refreshOutline(node.databaseId());
        }
    }

    @Override
    public void open(Node node, boolean data) {
        openNode(node, data);
    }

    @Override
    public void newQuery(Node node) {
        if (node == null) {
            return;
        }
        openSql(node.databaseId(), "SQL", "");
    }

    @Override
    public void newTable(Node node) {
        if (node == null) {
            return;
        }
        Session session = sessions.get(node.databaseId());
        if (session == null) {
            message("conecte o banco");
            return;
        }
        try {
            Dialogs.Named created = Dialogs.table(this, session.connection, session.outline.schemas(),
                    currentSchema(node.databaseId()));
            if (created != null) {
                refreshOutline(node.databaseId());
                openNode(new Node(Node.Kind.TABLE, node.databaseId(), created.name(), created.schema(), created.name()), false);
            }
        } catch (RuntimeException exception) {
            Dialogs.error(this, Dialogs.message(exception));
        }
    }

    @Override
    public void newSchema(Node node) {
        String name = Dialogs.ask(this, "Novo esquema", "Nome do esquema", "");
        if (name == null) {
            return;
        }
        run(node.databaseId(), "CREATE SCHEMA " + SqlNames.quote(name));
    }

    @Override
    public void useSchema(Node node) {
        Connection connection = connection(node.databaseId());
        if (connection == null) {
            return;
        }
        try {
            connection.setSchema(node.name());
            updateActions();
            message("esquema " + node.name());
        } catch (SQLException exception) {
            Dialogs.error(this, Dialogs.message(exception));
        }
    }

    @Override
    public void drop(Node node) {
        String sql = Ddl.dropObject(node.kind(), node.schema(), node.name());
        if (sql == null) {
            return;
        }
        if (!Dialogs.confirm(this, "Excluir " + node.title() + "?\n\n" + sql)) {
            return;
        }
        if (run(node.databaseId(), sql)) {
            closeObject(node);
        }
    }

    @Override
    public void newRole(Node node) {
        String name = Dialogs.ask(this, "Novo papel", "Nome do papel", "");
        if (name == null) {
            return;
        }
        run(node.databaseId(), "CREATE ROLE " + SqlNames.quote(name));
    }

    @Override
    public void extract(Node node) {
        Connection connection = connection(node.databaseId());
        if (connection == null) {
            return;
        }
        try {
            String sql = switch (node.kind()) {
                case TABLE, SYSTEM_TABLE -> Ddl.table(node.schema(), node.name(),
                        Outline.columns(connection, node.schema(), node.name()),
                        Outline.indexes(connection, node.schema(), node.name()));
                case VIEW -> {
                    Outline.ViewInfo view = Outline.view(connection, node.schema(), node.name());
                    yield Ddl.view(node.schema(), node.name(), view.columns(), view.source());
                }
                case PROCEDURE -> {
                    Outline.Routine routine = Outline.procedure(connection, node.name());
                    yield Ddl.procedure(routine.name(), routine.parameters(), routine.source());
                }
                case TRIGGER -> {
                    Outline.TriggerInfo trigger = null;
                    for (Outline.TriggerInfo item : Outline.triggers(connection)) {
                        if (SqlNames.same(item.name(), node.name())) {
                            trigger = item;
                        }
                    }
                    if (trigger == null) {
                        throw new SQLException("trigger não encontrado");
                    }
                    yield Ddl.trigger(trigger.name(), trigger.relation(), trigger.inactive(), trigger.type(),
                            trigger.position(), trigger.source());
                }
                case GENERATOR -> {
                    Outline.GeneratorInfo generator = Outline.generator(connection, node.name());
                    yield "CREATE GENERATOR " + SqlNames.quote(generator.name()) + ";\nSET GENERATOR "
                            + SqlNames.quote(generator.name()) + " TO " + generator.current();
                }
                default -> null;
            };
            if (sql != null) {
                openSql(node.databaseId(), "DDL " + node.name(), sql);
            }
        } catch (SQLException exception) {
            Dialogs.error(this, Dialogs.message(exception));
        }
    }

    @Override
    public void selectionChanged() {
        updateActions();
    }

    private boolean connectEntry(DatabaseEntry entry) {
        if (sessions.containsKey(entry.id())) {
            explorer.select(entry.id());
            updateActions();
            return true;
        }
        String password = entry.password();
        if (password.isEmpty()) {
            password = Dialogs.password(this, entry.label());
            if (password == null) {
                return false;
            }
        }
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        try {
            Properties properties = new Properties();
            properties.setProperty("user", entry.user());
            properties.setProperty("password", password);
            if (!entry.role().isBlank() && !entry.role().equalsIgnoreCase("NONE")) {
                properties.setProperty("roleName", entry.role());
            }
            Connection connection = DriverManager.getConnection(entry.url(), properties);
            connection.setAutoCommit(true);
            Outline outline = Outline.load(connection);
            sessions.put(entry.id(), new Session(entry.id(), connection, outline));
            if (!entry.password().equals(password)) {
                replace(new DatabaseEntry(entry.id(), entry.alias(), entry.embedded(), entry.path(), entry.host(),
                        entry.port(), entry.database(), entry.user(), password, entry.role(), entry.rememberPassword()));
                save();
            }
            explorer.show(entries, outlines(), entry.id());
            if (!outline.warnings().isEmpty()) {
                message(String.join(" ", outline.warnings()));
            } else {
                message("conectado a " + entry.label());
            }
            updateActions();
            return true;
        } catch (SQLException | RuntimeException exception) {
            Dialogs.error(this, Dialogs.message(exception));
            return false;
        } finally {
            setCursor(java.awt.Cursor.getDefaultCursor());
        }
    }

    private boolean run(String databaseId, String sql) {
        Connection connection = connection(databaseId);
        if (connection == null) {
            message("banco desconectado");
            return false;
        }
        try {
            Commands.run(connection, sql);
            refreshOutline(databaseId);
            message(sql);
            return true;
        } catch (SQLException exception) {
            Dialogs.error(this, Dialogs.message(exception));
            return false;
        }
    }

    private void executeQuery() {
        if (workspace.getSelectedComponent() instanceof QueryPane query) {
            query.execute();
            return;
        }
        message("abra uma consulta para executar");
    }

    private void transaction(boolean commit) {
        Connection connection = connection(activeId());
        if (connection == null) {
            return;
        }
        try {
            if (commit) {
                connection.commit();
                message("commit");
            } else {
                connection.rollback();
                message("rollback");
            }
        } catch (SQLException exception) {
            Dialogs.error(this, Dialogs.message(exception));
        }
    }

    private void toggleAutoCommit() {
        Connection connection = connection(activeId());
        if (connection == null) {
            autoCommit.setSelected(true);
            return;
        }
        try {
            connection.setAutoCommit(autoCommit.isSelected());
            message(autoCommit.isSelected() ? "auto-commit ligado" : "transação manual");
            updateActions();
        } catch (SQLException exception) {
            Dialogs.error(this, Dialogs.message(exception));
            updateActions();
        }
    }

    private void applySchema() {
        if (updatingSchema) {
            return;
        }
        Connection connection = connection(activeId());
        Object selected = schemas.getSelectedItem();
        if (connection == null || selected == null) {
            return;
        }
        try {
            connection.setSchema(selected.toString());
            statusSchema.setText(selected.toString());
        } catch (SQLException exception) {
            Dialogs.error(this, Dialogs.message(exception));
        }
    }

    private void newQueryFromStart() {
        Node database = explorer.selectedDatabase();
        if (database == null && !entries.isEmpty()) {
            database = Node.database(entries.get(0).id(), entries.get(0).label());
        }
        if (database == null) {
            message("registre um banco antes da consulta");
            return;
        }
        newQuery(database);
    }

    private void updateActions() {
        String id = activeId();
        Session session = id == null ? null : sessions.get(id);
        Node selectedDatabase = explorer.selectedDatabase();
        boolean treeConnected = selectedDatabase != null && sessions.containsKey(selectedDatabase.databaseId());
        connectButton.setEnabled(selectedDatabase != null && !treeConnected);
        disconnectButton.setEnabled(treeConnected);
        refreshButton.setEnabled(treeConnected);
        queryButton.setEnabled(selectedDatabase != null);
        runButton.setEnabled(workspace.getSelectedComponent() instanceof QueryPane);
        boolean manual = false;
        if (session != null) {
            try {
                manual = !session.connection.getAutoCommit();
            } catch (SQLException exception) {
                manual = false;
            }
        }
        commitButton.setEnabled(session != null && manual);
        rollbackButton.setEnabled(session != null && manual);
        autoCommit.setEnabled(session != null);
        updatingSchema = true;
        try {
            autoCommit.setSelected(session == null || !manual);
            schemas.removeAllItems();
            if (session != null) {
                List<String> names = session.outline.schemas();
                if (names.isEmpty()) {
                    schemas.addItem("public");
                }
                for (String schema : names) {
                    schemas.addItem(schema);
                }
                String current = currentSchema(session.id);
                schemas.setSelectedItem(current);
                DatabaseEntry entry = entry(session.id);
                statusConnection.setText(entry == null ? session.id : entry.label());
                try {
                    statusUser.setText(session.connection.getMetaData().getUserName());
                } catch (SQLException exception) {
                    statusUser.setText("");
                }
                statusSchema.setText(current);
                statusMode.setText(manual ? "transação manual" : "auto-commit");
                setTitle("Nexora Studio — " + statusConnection.getText());
            } else if (selectedDatabase != null) {
                statusConnection.setText(selectedDatabase.title());
                statusUser.setText("");
                statusSchema.setText("");
                statusMode.setText("desconectado");
                setTitle("Nexora Studio — " + selectedDatabase.title());
            } else {
                statusConnection.setText("sem banco");
                statusUser.setText("");
                statusSchema.setText("");
                statusMode.setText("");
                setTitle("Nexora Studio");
            }
        } finally {
            updatingSchema = false;
        }
        schemas.setEnabled(session != null);
    }

    private String activeId() {
        Component selected = workspace.getSelectedComponent();
        if (selected instanceof QueryPane query) {
            return query.databaseId();
        }
        if (selected instanceof ObjectPane object) {
            return object.databaseId();
        }
        Node database = explorer.selectedDatabase();
        return database == null ? null : database.databaseId();
    }

    private Map<String, Outline> outlines() {
        Map<String, Outline> map = new LinkedHashMap<>();
        for (Session session : sessions.values()) {
            map.put(session.id, session.outline);
        }
        return map;
    }

    private DatabaseEntry entry(String id) {
        for (DatabaseEntry entry : entries) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    private void replace(DatabaseEntry entry) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(entry.id())) {
                entries.set(i, entry);
            }
        }
    }

    private void closeObject(Node node) {
        for (int i = workspace.getTabCount() - 1; i >= 0; i--) {
            if (workspace.getComponentAt(i) instanceof ObjectPane pane && pane.same(node)) {
                workspace.remove(i);
            }
        }
    }

    private void save() {
        try {
            Registry.save(registryFile, entries);
        } catch (Exception exception) {
            Dialogs.error(this, "Não foi possível gravar o registro: " + Dialogs.message(exception));
        }
    }

    private void exit() {
        for (Session session : sessions.values()) {
            try {
                if (!session.connection.getAutoCommit()
                        && !Dialogs.confirm(this, "Sair? Uma transação aberta será desfeita.")) {
                    return;
                }
            } catch (SQLException exception) {
                break;
            }
        }
        for (Session session : new ArrayList<>(sessions.values())) {
            try {
                session.connection.close();
            } catch (SQLException ignored) {
                // a janela fecha mesmo se uma conexão já tiver caído
            }
        }
        sessions.clear();
        dispose();
    }

    private JMenuBar menu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("Arquivo");
        file.add(item("Registrar banco", this::register));
        file.add(item("Criar banco", this::createDatabase));
        file.addSeparator();
        file.add(item("Sair", this::exit));
        JMenu database = new JMenu("Banco");
        database.add(item("Conectar", () -> {
            Node node = explorer.selectedDatabase();
            if (node != null) {
                connect(node);
            }
        }));
        database.add(item("Desconectar", () -> {
            Node node = explorer.selectedDatabase();
            if (node != null) {
                disconnect(node);
            }
        }));
        database.add(item("Atualizar", () -> refresh(explorer.selectedDatabase())));
        database.addSeparator();
        database.add(item("Commit", () -> transaction(true)));
        database.add(item("Rollback", () -> transaction(false)));
        JMenu query = new JMenu("Consulta");
        JMenuItem created = item("Nova consulta", this::newQueryFromStart);
        created.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK));
        query.add(created);
        JMenuItem run = item("Executar", this::executeQuery);
        run.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F9, 0));
        query.add(run);
        JMenu help = new JMenu("Ajuda");
        help.add(item("Sobre", () -> javax.swing.JOptionPane.showMessageDialog(this, """
                Nexora Studio 1.0

                Interface administrativa do Nexora, no espírito do IBExpert.
                O banco é um arquivo .db com o log .log ao lado. A conexão embutida abre o arquivo
                neste processo. A conexão de rede fala com o nexora-server.
                """, "Sobre", javax.swing.JOptionPane.INFORMATION_MESSAGE)));
        bar.add(file);
        bar.add(database);
        bar.add(query);
        bar.add(help);
        return bar;
    }

    private JPanel toolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        bar.setBackground(new Color(0xF7F8FA));
        bar.setBorder(new MatteBorder(0, 0, 1, 0, Look.LINE));
        bar.add(Look.button("Registrar"));
        ((JButton) bar.getComponent(0)).addActionListener(event -> register());
        JButton create = Look.button("Criar banco");
        create.addActionListener(event -> createDatabase());
        connectButton.addActionListener(event -> {
            Node node = explorer.selectedDatabase();
            if (node != null) {
                connect(node);
            }
        });
        disconnectButton.addActionListener(event -> {
            Node node = explorer.selectedDatabase();
            if (node != null) {
                disconnect(node);
            }
        });
        refreshButton.addActionListener(event -> refresh(explorer.selectedDatabase()));
        queryButton.addActionListener(event -> {
            Node node = explorer.selectedDatabase();
            if (node != null) {
                newQuery(node);
            } else {
                newQueryFromStart();
            }
        });
        runButton.setToolTipText("Executar o script da consulta (F9)");
        runButton.addActionListener(event -> executeQuery());
        commitButton.addActionListener(event -> transaction(true));
        rollbackButton.addActionListener(event -> transaction(false));
        autoCommit.addActionListener(event -> toggleAutoCommit());
        schemas.addActionListener(event -> applySchema());
        schemas.setPrototypeDisplayValue("public-schema");
        bar.add(create);
        bar.add(connectButton);
        bar.add(disconnectButton);
        bar.add(separator());
        bar.add(refreshButton);
        bar.add(separator());
        bar.add(queryButton);
        bar.add(runButton);
        bar.add(separator());
        bar.add(commitButton);
        bar.add(rollbackButton);
        bar.add(autoCommit);
        bar.add(new JLabel("Esquema"));
        bar.add(schemas);
        return bar;
    }

    private JPanel statusBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        bar.setBackground(Look.NAVY);
        bar.setBorder(new EmptyBorder(5, 10, 5, 10));
        bar.add(statusConnection);
        bar.add(gap());
        bar.add(statusUser);
        bar.add(gap());
        bar.add(statusSchema);
        bar.add(gap());
        bar.add(statusMode);
        bar.add(gap());
        bar.add(statusMessage);
        return bar;
    }

    private void bindKeys() {
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F9, 0), "run", this::executeQuery);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "run-ctrl", this::executeQuery);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "refresh", () -> refresh(explorer.selectedDatabase()));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK), "new-sql", this::newQueryFromStart);
    }

    private void bind(KeyStroke stroke, String name, Runnable action) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, name);
        getRootPane().getActionMap().put(name, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                action.run();
            }
        });
    }

    private void addTab(String title, JComponent page, boolean closable) {
        workspace.addTab(title, page);
        int index = workspace.indexOfComponent(page);
        workspace.setTabComponentAt(index, new TabHeader(title, closable));
        workspace.setSelectedComponent(page);
    }

    private static JMenuItem item(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(event -> action.run());
        return item;
    }

    private static JLabel statusLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Color.WHITE);
        label.setFont(Look.UI);
        return label;
    }

    private static JLabel gap() {
        JLabel label = new JLabel("    ");
        return label;
    }

    private static JPanel separator() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setPreferredSize(new Dimension(8, 8));
        return panel;
    }

    private final class TabHeader extends JPanel {
        TabHeader(String title, boolean closable) {
            super(new FlowLayout(FlowLayout.LEFT, 4, 0));
            setOpaque(false);
            JLabel label = new JLabel(title);
            label.setFont(Look.UI);
            add(label);
            if (closable) {
                JButton close = new JButton("×");
                close.setFont(Look.UI);
                close.setBorder(new EmptyBorder(0, 4, 0, 0));
                close.setContentAreaFilled(false);
                close.setFocusPainted(false);
                close.addActionListener(event -> {
                    int index = workspace.indexOfTabComponent(TabHeader.this);
                    if (index >= 0) {
                        workspace.remove(index);
                    }
                });
                add(close);
            }
        }
    }

    private static final class Session {
        final String id;
        final Connection connection;
        Outline outline;

        Session(String id, Connection connection, Outline outline) {
            this.id = id;
            this.connection = connection;
            this.outline = outline;
        }
    }
}
