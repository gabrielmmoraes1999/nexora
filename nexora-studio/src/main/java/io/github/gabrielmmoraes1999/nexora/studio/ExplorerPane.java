package io.github.gabrielmmoraes1999.nexora.studio;

import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ExplorerPane extends JPanel {

    interface Listener {
        void register();

        void createDatabase();

        void connect(Node node);

        void disconnect(Node node);

        void edit(Node node);

        void remove(Node node);

        void refresh(Node node);

        void open(Node node, boolean data);

        void newQuery(Node node);

        void newTable(Node node);

        void newSchema(Node node);

        void useSchema(Node node);

        void drop(Node node);

        void newRole(Node node);

        void extract(Node node);

        void selectionChanged();
    }

    private final Listener listener;
    private final JTree tree = new JTree(new DefaultMutableTreeNode("Bancos"));
    private final PromptField filter = new PromptField("Filtrar objetos");
    private final JLabel empty = new JLabel("Nenhum banco. Use Registrar ou Criar banco.");
    private final Set<String> expanded = new HashSet<>();
    private String focusId = "";

    ExplorerPane(Listener listener) {
        super(new BorderLayout());
        this.listener = listener;
        setBackground(Look.PAPER);
        setPreferredSize(new Dimension(300, 400));
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Look.NAVY);
        header.setBorder(new EmptyBorder(8, 12, 8, 12));
        JLabel title = new JLabel("Explorador");
        title.setForeground(java.awt.Color.WHITE);
        title.setFont(Look.UI_BOLD);
        header.add(title, BorderLayout.CENTER);
        JPanel filterBar = new JPanel(new BorderLayout());
        filterBar.setBorder(new EmptyBorder(8, 8, 8, 8));
        filterBar.setBackground(Look.PAPER);
        filter.setFont(Look.UI);
        filterBar.add(filter, BorderLayout.CENTER);
        JPanel north = new JPanel(new BorderLayout());
        north.add(header, BorderLayout.NORTH);
        north.add(filterBar, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(24);
        tree.setBackground(new java.awt.Color(0xF7F9FB));
        tree.setFont(Look.UI);
        tree.setCellRenderer(new Renderer());
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
        empty.setBorder(new EmptyBorder(8, 12, 12, 12));
        empty.setForeground(Look.MUTED);
        empty.setFont(Look.UI);
        add(empty, BorderLayout.SOUTH);

        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                capture();
                rebuild();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                capture();
                rebuild();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                capture();
                rebuild();
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                popup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                popup(event);
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    activate(nodeAt(event));
                }
            }
        });
        tree.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "open-node");
        tree.getActionMap().put("open-node", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                activate(selected());
            }
        });
        tree.addTreeSelectionListener(event -> listener.selectionChanged());
    }

    private List<DatabaseEntry> entries = List.of();
    private Map<String, Outline> outlines = Map.of();

    void show(List<DatabaseEntry> entries, Map<String, Outline> outlines, String focusId) {
        capture();
        this.entries = List.copyOf(entries);
        this.outlines = Map.copyOf(outlines);
        this.focusId = focusId == null ? "" : focusId;
        if (!this.focusId.isBlank()) {
            expanded.add(Node.database(this.focusId, "").key());
            expanded.add(Node.folder(this.focusId, Node.SCHEMAS, "").key());
            expanded.add(new Node(Node.Kind.SCHEMA, this.focusId, "public", "public", "public").key());
        }
        rebuild();
        if (!this.focusId.isBlank()) {
            select(this.focusId);
        }
    }

    Node selected() {
        TreePath path = tree.getSelectionPath();
        if (path == null || !(path.getLastPathComponent() instanceof DefaultMutableTreeNode node)) {
            return null;
        }
        return node.getUserObject() instanceof Node item ? item : null;
    }

    Node selectedDatabase() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return null;
        }
        Object[] nodes = path.getPath();
        for (int i = nodes.length - 1; i >= 0; i--) {
            if (nodes[i] instanceof DefaultMutableTreeNode treeNode
                    && treeNode.getUserObject() instanceof Node item
                    && item.kind() == Node.Kind.DATABASE) {
                return item;
            }
        }
        return null;
    }

    void select(String databaseId) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        Enumeration<?> children = root.depthFirstEnumeration();
        while (children.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) children.nextElement();
            if (node.getUserObject() instanceof Node item
                    && item.kind() == Node.Kind.DATABASE
                    && item.databaseId().equals(databaseId)) {
                TreePath path = new TreePath(node.getPath());
                tree.setSelectionPath(path);
                tree.scrollPathToVisible(path);
                return;
            }
        }
    }

    private void rebuild() {
        String query = filter.getText().trim().toLowerCase(java.util.Locale.ROOT);
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Bancos");
        for (DatabaseEntry entry : entries) {
            Outline outline = outlines.get(entry.id());
            Node database = Node.database(entry.id(), entry.label());
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(database);
            if (outline != null) {
                addSchemas(node, entry.id(), outline, query);
                addNames(node, entry.id(), Node.PROCEDURES, "Procedures", outline.procedures(), Node.Kind.PROCEDURE, query);
                addGenerators(node, entry.id(), outline, query);
                addNames(node, entry.id(), Node.ROLES, "Papéis", outline.roles(), Node.Kind.ROLE, query);
                addNames(node, entry.id(), Node.USERS, "Usuários", outline.users(), Node.Kind.USER, query);
                addGroup(node, entry.id(), "", Node.SYSTEM, "Sistema", outline.systemTables(), Node.Kind.SYSTEM_TABLE, query);
                addLeaf(node, new Node(Node.Kind.CONNECTIONS, entry.id(), "Conexões", "", "connections"), query);
                addLeaf(node, new Node(Node.Kind.PRIVILEGES, entry.id(), "Privilégios", "", "privileges"), query);
                addLeaf(node, new Node(Node.Kind.CONFIG, entry.id(), "Configuração", "", "config"), query);
                addLeaf(node, new Node(Node.Kind.INFO, entry.id(), "Propriedades", "", "info"), query);
            }
            if (query.isBlank() || contains(node, query) || database.title().toLowerCase(java.util.Locale.ROOT).contains(query)) {
                if (!query.isBlank()) {
                    prune(node, query);
                }
                root.add(node);
            }
        }
        tree.setModel(new DefaultTreeModel(root));
        restore(root);
        empty.setVisible(entries.isEmpty());
    }

    private void addSchemas(DefaultMutableTreeNode parent, String databaseId, Outline outline, String query) {
        DefaultMutableTreeNode group = new DefaultMutableTreeNode(Node.folder(databaseId, Node.SCHEMAS, "Esquemas"));
        for (String schema : outline.schemas()) {
            DefaultMutableTreeNode schemaNode = new DefaultMutableTreeNode(
                    new Node(Node.Kind.SCHEMA, databaseId, schema, schema, schema));
            addGroup(schemaNode, databaseId, schema, Node.TABLES, "Tabelas",
                    relationsIn(outline.tables(), schema), Node.Kind.TABLE, query);
            addGroup(schemaNode, databaseId, schema, Node.VIEWS, "Visões",
                    relationsIn(outline.views(), schema), Node.Kind.VIEW, query);
            addTriggers(schemaNode, databaseId, schema, outline, query);
            if (query.isBlank() || schema.toLowerCase(java.util.Locale.ROOT).contains(query) || schemaNode.getChildCount() > 0) {
                group.add(schemaNode);
            }
        }
        if (query.isBlank() || group.getChildCount() > 0 || "esquemas".contains(query)) {
            parent.add(group);
        }
    }

    private static List<Outline.RelationName> relationsIn(List<Outline.RelationName> relations, String schema) {
        List<Outline.RelationName> found = new ArrayList<>();
        for (Outline.RelationName relation : relations) {
            if (relation.schema().equalsIgnoreCase(schema)) {
                found.add(relation);
            }
        }
        return found;
    }

    private void addGroup(DefaultMutableTreeNode parent, String databaseId, String schema, String folder, String title,
                           List<Outline.RelationName> relations, Node.Kind kind, String query) {
        DefaultMutableTreeNode group = new DefaultMutableTreeNode(Node.folder(databaseId, schema, folder, title));
        for (Outline.RelationName relation : relations) {
            String label = relation.name();
            Node node = new Node(kind, databaseId, label, relation.schema(), relation.name());
            if (query.isBlank() || label.toLowerCase(java.util.Locale.ROOT).contains(query)) {
                group.add(new DefaultMutableTreeNode(node));
            }
        }
        if (query.isBlank() || group.getChildCount() > 0 || title.toLowerCase(java.util.Locale.ROOT).contains(query)) {
            parent.add(group);
        }
    }

    private void addNames(DefaultMutableTreeNode parent, String databaseId, String folder, String title,
                          List<String> names, Node.Kind kind, String query) {
        DefaultMutableTreeNode group = new DefaultMutableTreeNode(Node.folder(databaseId, folder, title));
        for (String name : names) {
            if (query.isBlank() || name.toLowerCase(java.util.Locale.ROOT).contains(query)) {
                String schema = kind == Node.Kind.SCHEMA ? name : "";
                group.add(new DefaultMutableTreeNode(new Node(kind, databaseId, name, schema, name)));
            }
        }
        if (query.isBlank() || group.getChildCount() > 0 || title.toLowerCase(java.util.Locale.ROOT).contains(query)) {
            parent.add(group);
        }
    }

    private void addTriggers(DefaultMutableTreeNode parent, String databaseId, String schema, Outline outline, String query) {
        DefaultMutableTreeNode group = new DefaultMutableTreeNode(
                Node.folder(databaseId, schema, Node.TRIGGERS, "Triggers"));
        for (Outline.TriggerRef trigger : outline.triggers()) {
            if (!relationSchema(trigger.relation()).equalsIgnoreCase(schema)) {
                continue;
            }
            String label = trigger.inactive() ? trigger.name() + " (inativo)" : trigger.name();
            if (query.isBlank() || label.toLowerCase(java.util.Locale.ROOT).contains(query)
                    || trigger.relation().toLowerCase(java.util.Locale.ROOT).contains(query)) {
                group.add(new DefaultMutableTreeNode(new Node(Node.Kind.TRIGGER, databaseId, label, schema, trigger.name())));
            }
        }
        if (query.isBlank() || group.getChildCount() > 0) {
            parent.add(group);
        }
    }

    private static String relationSchema(String relation) {
        if (relation == null || relation.isBlank()) {
            return "public";
        }
        int dot = relation.indexOf('.');
        if (dot <= 0) {
            return "public";
        }
        return relation.substring(0, dot);
    }

    private void addGenerators(DefaultMutableTreeNode parent, String databaseId, Outline outline, String query) {
        DefaultMutableTreeNode group = new DefaultMutableTreeNode(Node.folder(databaseId, Node.GENERATORS, "Geradores"));
        for (Outline.GeneratorRef generator : outline.generators()) {
            String label = generator.name() + " = " + generator.current();
            if (query.isBlank() || generator.name().toLowerCase(java.util.Locale.ROOT).contains(query)) {
                group.add(new DefaultMutableTreeNode(new Node(Node.Kind.GENERATOR, databaseId, label, "", generator.name())));
            }
        }
        if (query.isBlank() || group.getChildCount() > 0) {
            parent.add(group);
        }
    }

    private void addLeaf(DefaultMutableTreeNode parent, Node node, String query) {
        if (query.isBlank() || node.title().toLowerCase(java.util.Locale.ROOT).contains(query)) {
            parent.add(new DefaultMutableTreeNode(node));
        }
    }

    private static boolean contains(DefaultMutableTreeNode node, String query) {
        if (node.getUserObject() instanceof Node item
                && item.title().toLowerCase(java.util.Locale.ROOT).contains(query)) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (contains((DefaultMutableTreeNode) node.getChildAt(i), query)) {
                return true;
            }
        }
        return false;
    }

    private static void prune(DefaultMutableTreeNode node, String query) {
        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            prune(child, query);
            boolean self = child.getUserObject() instanceof Node item
                    && item.title().toLowerCase(java.util.Locale.ROOT).contains(query);
            if (!self && child.getChildCount() == 0 && child.getUserObject() instanceof Node item
                    && item.kind() == Node.Kind.FOLDER) {
                node.remove(i);
            }
        }
    }

    private void capture() {
        if (!(tree.getModel().getRoot() instanceof DefaultMutableTreeNode root)) {
            return;
        }
        Enumeration<TreePath> paths = tree.getExpandedDescendants(new TreePath(root));
        if (paths == null) {
            return;
        }
        while (paths.hasMoreElements()) {
            Object last = paths.nextElement().getLastPathComponent();
            if (last instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof Node item) {
                expanded.add(item.key());
            }
        }
    }

    private void restore(DefaultMutableTreeNode root) {
        Enumeration<?> nodes = root.depthFirstEnumeration();
        List<TreePath> paths = new ArrayList<>();
        while (nodes.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
            if (node.getUserObject() instanceof Node item && (expanded.contains(item.key()) || !filter.getText().isBlank())) {
                paths.add(new TreePath(node.getPath()));
            }
        }
        for (TreePath path : paths) {
            tree.expandPath(path);
        }
    }

    private void activate(Node node) {
        if (node == null) {
            return;
        }
        switch (node.kind()) {
            case DATABASE -> {
                if (!outlines.containsKey(node.databaseId())) {
                    listener.connect(node);
                }
            }
            case FOLDER -> {
                if (Node.USERS.equals(node.name())) {
                    listener.open(node, false);
                }
            }
            case SCHEMA -> listener.useSchema(node);
            case TABLE, VIEW -> listener.open(node, false);
            case SYSTEM_TABLE, PROCEDURE, TRIGGER, GENERATOR, USER, ROLE, CONNECTIONS, PRIVILEGES, CONFIG, INFO ->
                    listener.open(node, false);
            default -> {
            }
        }
    }

    private void popup(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        Node node = nodeAt(event);
        JPopupMenu menu = new JPopupMenu();
        if (node == null) {
            item(menu, "Registrar banco", listener::register);
            item(menu, "Criar banco", listener::createDatabase);
            menu.show(tree, event.getX(), event.getY());
            return;
        }
        Node database = databaseOf(node);
        boolean connected = database != null && outlines.containsKey(database.databaseId());
        switch (node.kind()) {
            case DATABASE -> {
                if (connected) {
                    item(menu, "Nova consulta", () -> listener.newQuery(node));
                    item(menu, "Nova tabela", () -> listener.newTable(node));
                    item(menu, "Atualizar", () -> listener.refresh(node));
                    item(menu, "Desconectar", () -> listener.disconnect(node));
                } else {
                    item(menu, "Conectar", () -> listener.connect(node));
                }
                item(menu, "Propriedades do registro", () -> listener.edit(node));
                item(menu, "Remover do registro", () -> listener.remove(node));
            }
            case FOLDER -> {
                if (Node.TABLES.equals(node.name())) {
                    item(menu, "Nova tabela", () -> listener.newTable(node));
                }
                if (Node.SCHEMAS.equals(node.name())) {
                    item(menu, "Novo esquema", () -> listener.newSchema(database));
                }
                if (Node.ROLES.equals(node.name())) {
                    item(menu, "Novo papel", () -> listener.newRole(database));
                }
                if (Node.USERS.equals(node.name())) {
                    item(menu, "Usuários", () -> listener.open(node, false));
                }
                item(menu, "Atualizar", () -> listener.refresh(database));
            }
            case TABLE, VIEW -> {
                item(menu, "Abrir", () -> listener.open(node, false));
                item(menu, "Dados", () -> listener.open(node, true));
                item(menu, "DDL", () -> listener.extract(node));
                if (node.kind() == Node.Kind.TABLE) {
                    item(menu, "Nova tabela", () -> listener.newTable(node));
                }
                item(menu, "Excluir", () -> listener.drop(node));
            }
            case PROCEDURE, TRIGGER, GENERATOR, ROLE -> {
                item(menu, "Abrir", () -> listener.open(node, false));
                item(menu, "DDL", () -> listener.extract(node));
                item(menu, "Excluir", () -> listener.drop(node));
            }
            case SCHEMA -> {
                item(menu, "Usar nesta conexão", () -> listener.useSchema(node));
                item(menu, "Nova tabela", () -> listener.newTable(node));
                if (!node.name().equalsIgnoreCase("public")) {
                    item(menu, "Excluir", () -> listener.drop(node));
                }
            }
            case SYSTEM_TABLE -> {
                item(menu, "Abrir", () -> listener.open(node, false));
                item(menu, "Dados", () -> listener.open(node, true));
            }
            default -> item(menu, "Abrir", () -> listener.open(node, false));
        }
        menu.show(tree, event.getX(), event.getY());
    }

    private Node databaseOf(Node node) {
        if (node.kind() == Node.Kind.DATABASE) {
            return node;
        }
        return Node.database(node.databaseId(), "");
    }

    private Node nodeAt(MouseEvent event) {
        TreePath path = tree.getPathForLocation(event.getX(), event.getY());
        if (path == null) {
            tree.clearSelection();
            return null;
        }
        tree.setSelectionPath(path);
        if (path.getLastPathComponent() instanceof DefaultMutableTreeNode node
                && node.getUserObject() instanceof Node item) {
            return item;
        }
        return null;
    }

    private static void item(JPopupMenu menu, String label, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(event -> action.run());
        menu.add(item);
    }

    private final class Renderer extends DefaultTreeCellRenderer {
        Renderer() {
            setBackgroundNonSelectionColor(new java.awt.Color(0xF7F9FB));
            setBackgroundSelectionColor(Look.SELECTION);
            setTextSelectionColor(Look.INK);
            setTextNonSelectionColor(Look.INK);
            setBorderSelectionColor(null);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                        boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
            if (value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof Node item) {
                boolean connected = outlines.containsKey(item.databaseId());
                setIcon(Icons.of(item, connected && item.kind() == Node.Kind.DATABASE || item.kind() != Node.Kind.DATABASE && connected));
                setFont(item.kind() == Node.Kind.DATABASE ? Look.UI_BOLD : Look.UI);
                if (item.kind() == Node.Kind.DATABASE) {
                    setText(item.title() + (connected ? "" : "  ·  desconectado"));
                }
            }
            return this;
        }
    }

    private static final class PromptField extends JTextField {
        private final String prompt;

        PromptField(String prompt) {
            this.prompt = prompt;
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent event) {
                    repaint();
                }

                @Override
                public void focusLost(java.awt.event.FocusEvent event) {
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (!getText().isEmpty() || isFocusOwner()) {
                return;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            g.setFont(getFont());
            g.setColor(Look.MUTED);
            Insets insets = getInsets();
            java.awt.FontMetrics metrics = g.getFontMetrics();
            g.drawString(prompt, insets.left, (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
            g.dispose();
        }
    }
}
