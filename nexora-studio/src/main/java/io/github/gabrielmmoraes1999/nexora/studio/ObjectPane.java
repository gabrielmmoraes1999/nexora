package io.github.gabrielmmoraes1999.nexora.studio;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

final class ObjectPane extends JPanel {

    private final Hub hub;
    private final Node node;
    private JTabbedPane tabs;
    private DataPane data;
    private JTable primaryKeys;
    private JTable foreignKeys;
    private JTable uniqueKeys;

    ObjectPane(Hub hub, Node node, boolean dataFirst) {
        super(new BorderLayout());
        this.hub = hub;
        this.node = node;
        add(header(), BorderLayout.NORTH);
        add(body(dataFirst), BorderLayout.CENTER);
    }

    String databaseId() {
        return node.databaseId();
    }

    Node node() {
        return node;
    }

    boolean same(Node other) {
        return node.sameObject(other);
    }

    void showData() {
        if (tabs != null && data != null) {
            tabs.setSelectedComponent(data);
            if (!data.loaded()) {
                data.load();
            }
        }
    }

    private JPanel header() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(java.awt.Color.WHITE);
        panel.setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 1, 0, Look.LINE),
                new EmptyBorder(10, 14, 10, 14)));
        JLabel title = new JLabel(caption(), Icons.of(node, true), SwingConstants.LEADING);
        title.setIconTextGap(8);
        title.setFont(Look.UI_BOLD);
        title.setForeground(Look.NAVY);
        panel.add(title, BorderLayout.WEST);
        return panel;
    }

    private String caption() {
        String kind = switch (node.kind()) {
            case TABLE -> "Table";
            case SYSTEM_TABLE -> "System table";
            case VIEW -> "View";
            case PROCEDURE -> "Procedure";
            case TRIGGER -> "Trigger";
            case GENERATOR -> "Generator";
            case ROLE -> "Role";
            case USER, FOLDER -> "Users";
            case CONNECTIONS -> "Connections";
            case PRIVILEGES -> "Privileges";
            case CONFIG -> "Configuration";
            case INFO -> "Properties";
            default -> "Object";
        };
        String name = node.place().isBlank() ? node.title() : node.place();
        return kind + "   " + name;
    }

    private java.awt.Component body(boolean dataFirst) {
        return switch (node.kind()) {
            case TABLE, SYSTEM_TABLE -> relation(node.kind() == Node.Kind.TABLE, dataFirst);
            case VIEW -> view(dataFirst);
            case PROCEDURE -> procedure();
            case TRIGGER -> trigger();
            case GENERATOR -> generator();
            case ROLE -> role();
            case USER, FOLDER -> users();
            case CONNECTIONS -> connections();
            case PRIVILEGES -> privileges(null, null);
            case CONFIG -> config();
            case INFO -> info();
            default -> new JLabel("Nothing to open for this item.");
        };
    }

    private java.awt.Component relation(boolean editableShape, boolean dataFirst) {
        tabs = new JTabbedPane();
        JTable columns = Tables.create();
        JTable indexes = Tables.create();
        JTable triggers = Tables.create();
        JTable grants = Tables.create();
        JTextArea ddl = scriptArea();
        data = new DataPane(hub, node.databaseId(), node.schema(), node.name(), editableShape);
        tabs.addTab("Columns", wrap(columns, buttons(
                button("New column", () -> {
                    Connection connection = requireQuiet();
                    if (connection != null && Dialogs.column(hub.owner(), connection, node.schema(), node.name())) {
                        hub.refreshOutline(node.databaseId());
                        reloadColumns(columns);
                        reloadDdl(ddl);
                        try {
                            refreshConstraints(connection);
                        } catch (SQLException exception) {
                            Dialogs.error(hub.owner(), Dialogs.message(exception));
                        }
                    }
                }, editableShape),
                button("Delete column", () -> dropColumn(columns, ddl), editableShape),
                button("Move up", () -> moveColumn(columns, ddl, -1), editableShape),
                button("Move down", () -> moveColumn(columns, ddl, 1), editableShape))));
        tabs.addTab("Data", data);
        tabs.addTab("Indexes", wrap(indexes, buttons(
                button("New index", () -> newIndex(indexes, ddl), editableShape),
                button("Delete index", () -> dropIndex(indexes, ddl), editableShape))));
        JTabbedPane constraints = new JTabbedPane();
        primaryKeys = Tables.create();
        foreignKeys = Tables.create();
        uniqueKeys = Tables.create();
        constraints.addTab("Primary key", wrap(Look.scroll(primaryKeys), buttons(
                button("Delete", () -> dropConstraint(primaryKeys, columns, indexes, ddl), editableShape))));
        constraints.addTab("Foreign keys", wrap(Look.scroll(foreignKeys), buttons(
                button("Delete", () -> dropConstraint(foreignKeys, columns, indexes, ddl), editableShape))));
        constraints.addTab("Uniques", wrap(Look.scroll(uniqueKeys), buttons(
                button("Delete", () -> dropConstraint(uniqueKeys, columns, indexes, ddl), editableShape))));
        tabs.addTab("Constraints", constraints);
        tabs.addTab("Triggers", Look.scroll(triggers));
        tabs.addTab("Privileges", wrap(grants, buttons(
                button("Grant", () -> {
                    if (Dialogs.grant(hub.owner(), requireQuiet(), node.schema(), node.name())) {
                        reloadGrants(grants);
                    }
                }, true),
                button("Revoke", () -> revoke(grants), true))));
        tabs.addTab("DDL", wrap(scriptScroll(ddl), buttons(
                button("Copy", () -> copy(ddl.getText()), true),
                button("Open in editor", () -> hub.openSql(node.databaseId(), "DDL " + node.name(), ddl.getText()), true))));
        tabs.addChangeListener(event -> {
            if (tabs.getSelectedComponent() == data && !data.loaded()) {
                data.load();
            }
        });
        loadRelation(columns, indexes, triggers, grants, ddl);
        if (dataFirst) {
            tabs.setSelectedComponent(data);
            data.load();
        }
        return tabs;
    }

    private void loadRelation(JTable columns, JTable indexes, JTable triggers, JTable grants, JTextArea ddl) {
        Connection connection = hub.connection(node.databaseId());
        if (connection == null) {
            return;
        }
        try {
            fillColumns(columns, Outline.columns(connection, node.schema(), node.name()));
            fillIndexes(indexes, Outline.indexes(connection, node.schema(), node.name()));
            refreshConstraints(connection);
            String catalog = SqlNames.catalog(node.schema(), node.name());
            List<List<Object>> triggerRows = new ArrayList<>();
            for (Outline.TriggerInfo trigger : Outline.triggers(connection)) {
                if (SqlNames.same(trigger.relation(), catalog) || SqlNames.same(trigger.relation(), node.name())) {
                    triggerRows.add(List.of(trigger.name(), trigger.type(), trigger.position(),
                            trigger.inactive() ? "no" : "yes", trigger.owner()));
                }
            }
            Tables.show(triggers, List.of("Name", "When", "Position", "Active", "Owner"), triggerRows);
            fillGrants(grants, connection, catalog);
            ddl.setText(script(connection));
            ddl.setCaretPosition(0);
        } catch (SQLException exception) {
            Dialogs.error(hub.owner(), Dialogs.message(exception));
        }
    }

    private java.awt.Component view(boolean dataFirst) {
        tabs = new JTabbedPane();
        JTextArea source = scriptArea();
        JTextArea ddl = scriptArea();
        data = new DataPane(hub, node.databaseId(), node.schema(), node.name(), false);
        tabs.addTab("Code", wrap(scriptScroll(source), buttons(
                button("Open in editor", () -> hub.openSql(node.databaseId(), node.name(), source.getText()), true))));
        tabs.addTab("Data", data);
        tabs.addTab("DDL", wrap(scriptScroll(ddl), buttons(
                button("Copy", () -> copy(ddl.getText()), true))));
        Connection connection = hub.connection(node.databaseId());
        if (connection != null) {
            try {
                Outline.ViewInfo view = Outline.view(connection, node.schema(), node.name());
                source.setText(view.source());
                ddl.setText(Ddl.view(node.schema(), node.name(), view.columns(), view.source()));
                source.setCaretPosition(0);
                ddl.setCaretPosition(0);
            } catch (SQLException exception) {
                Dialogs.error(hub.owner(), Dialogs.message(exception));
            }
        }
        if (dataFirst) {
            tabs.setSelectedComponent(data);
            data.load();
        } else {
            tabs.addChangeListener(event -> {
                if (tabs.getSelectedComponent() == data && !data.loaded()) {
                    data.load();
                }
            });
        }
        return tabs;
    }

    private java.awt.Component procedure() {
        JTextArea source = scriptArea();
        JTable parameters = Tables.create();
        Connection connection = hub.connection(node.databaseId());
        if (connection != null) {
            try {
                Outline.Routine routine = Outline.procedure(connection, node.schema(), node.name());
                source.setText(Ddl.procedure(node.schema(), routine.name(), routine.parameters(), routine.source()));
                source.setCaretPosition(0);
                List<List<Object>> rows = new ArrayList<>();
                for (Ddl.Field field : routine.parameters()) {
                    rows.add(List.of(field.input() ? "input" : "output", field.name(),
                            Ddl.typeSql(field.typeName(), field.size(), field.scale())));
                }
                Tables.show(parameters, List.of("Direction", "Name", "Type"), rows);
            } catch (SQLException exception) {
                Dialogs.error(hub.owner(), Dialogs.message(exception));
            }
        }
        JTabbedPane pages = new JTabbedPane();
        pages.addTab("Code", wrap(scriptScroll(source), buttons(
                button("Open in editor", () -> hub.openSql(node.databaseId(), node.name(), source.getText()), true),
                button("Copy", () -> copy(source.getText()), true))));
        pages.addTab("Parameters", Look.scroll(parameters));
        return pages;
    }

    private java.awt.Component trigger() {
        JTextArea source = scriptArea();
        JLabel state = new JLabel(" ");
        JButton toggle = Look.button("Activate");
        Connection connection = hub.connection(node.databaseId());
        if (connection != null) {
            try {
                Outline.TriggerInfo trigger = findTrigger(connection, node.name());
                source.setText(Ddl.trigger(trigger.name(), trigger.relation(), trigger.inactive(), trigger.type(),
                        trigger.position(), trigger.source()));
                source.setCaretPosition(0);
                state.setText(trigger.inactive() ? "Inactive" : "Active");
                toggle.setText(trigger.inactive() ? "Activate" : "Deactivate");
                boolean[] inactive = {trigger.inactive()};
                toggle.addActionListener(event -> {
                    Connection current = hub.connection(node.databaseId());
                    if (current == null) {
                        return;
                    }
                    try {
                        Commands.run(current, "ALTER TRIGGER " + SqlNames.quote(node.name()) + " "
                                + (inactive[0] ? "ACTIVE" : "INACTIVE"));
                        inactive[0] = !inactive[0];
                        toggle.setText(inactive[0] ? "Activate" : "Deactivate");
                        state.setText(inactive[0] ? "Inactive" : "Active");
                        hub.refreshOutline(node.databaseId());
                        hub.message(inactive[0] ? "trigger deactivated" : "trigger activated");
                    } catch (SQLException exception) {
                        Dialogs.error(hub.owner(), Dialogs.message(exception));
                    }
                });
            } catch (SQLException exception) {
                Dialogs.error(hub.owner(), Dialogs.message(exception));
            }
        }
        return wrap(scriptScroll(source), buttons(toggle,
                button("Open in editor", () -> hub.openSql(node.databaseId(), node.name(), source.getText()), true)));
    }

    private java.awt.Component generator() {
        JPanel panel = new JPanel(new java.awt.GridBagLayout());
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));
        panel.setOpaque(false);
        JLabel current = new JLabel("—");
        current.setFont(Look.UI_BOLD);
        JTextField value = new JTextField(12);
        JButton apply = Look.primary("Set value");
        Connection connection = hub.connection(node.databaseId());
        if (connection != null) {
            try {
                Outline.GeneratorInfo generator = Outline.generator(connection, node.schema(), node.name());
                current.setText(Long.toString(generator.current()));
                apply.addActionListener(event -> {
                    Connection live = hub.connection(node.databaseId());
                    if (live == null) {
                        return;
                    }
                    try {
                        long next = Long.parseLong(value.getText().trim());
                        Commands.run(live, "SET GENERATOR " + SqlNames.qualify(node.schema(), node.name()) + " TO " + next);
                        current.setText(Long.toString(Outline.generator(live, node.schema(), node.name()).current()));
                        hub.refreshOutline(node.databaseId());
                    } catch (RuntimeException | SQLException exception) {
                        Dialogs.error(hub.owner(), Dialogs.message(exception));
                    }
                });
                java.awt.GridBagConstraints constraints = new java.awt.GridBagConstraints();
                constraints.insets = new java.awt.Insets(4, 4, 4, 4);
                constraints.anchor = java.awt.GridBagConstraints.LINE_START;
                constraints.gridx = 0;
                constraints.gridy = 0;
                panel.add(new JLabel("Current value"), constraints);
                constraints.gridx = 1;
                panel.add(current, constraints);
                constraints.gridy = 1;
                constraints.gridx = 0;
                panel.add(new JLabel("Start " + generator.initial() + "   step " + generator.increment()
                        + "   owner " + generator.owner()), constraints);
                constraints.gridwidth = 2;
                constraints.gridy = 2;
                panel.add(value, constraints);
                constraints.gridy = 3;
                panel.add(apply, constraints);
            } catch (SQLException exception) {
                return new JLabel(Dialogs.message(exception));
            }
        }
        return panel;
    }

    private java.awt.Component role() {
        JTable members = Tables.create();
        Runnable load = () -> {
            Connection connection = hub.connection(node.databaseId());
            if (connection == null) {
                return;
            }
            try {
                List<List<Object>> rows = new ArrayList<>();
                for (Outline.MemberInfo member : Outline.members(connection)) {
                    if (SqlNames.same(member.role(), node.name())) {
                        rows.add(List.of(member.user(), member.adminOption() ? "yes" : "no", member.grantor()));
                    }
                }
                Tables.show(members, List.of("User", "Admin", "Granted by"), rows);
            } catch (SQLException exception) {
                Dialogs.error(hub.owner(), Dialogs.message(exception));
            }
        };
        load.run();
        JButton grant = Look.button("Grant to user");
        grant.addActionListener(event -> {
            String user = Dialogs.ask(hub.owner(), "Grant role", "User or PUBLIC", "");
            if (user == null) {
                return;
            }
            int choice = javax.swing.JOptionPane.showConfirmDialog(hub.owner(),
                    "Grant ADMIN OPTION to " + user + "?", "Role",
                    javax.swing.JOptionPane.YES_NO_CANCEL_OPTION);
            if (choice == javax.swing.JOptionPane.CANCEL_OPTION || choice == javax.swing.JOptionPane.CLOSED_OPTION) {
                return;
            }
            Connection connection = hub.connection(node.databaseId());
            if (connection == null) {
                return;
            }
            try {
                Commands.run(connection, Ddl.grantRole(node.name(), user, choice == javax.swing.JOptionPane.YES_OPTION));
                load.run();
                hub.refreshOutline(node.databaseId());
            } catch (SQLException exception) {
                Dialogs.error(hub.owner(), Dialogs.message(exception));
            }
        });
        return wrap(Look.scroll(members), buttons(grant));
    }

    private java.awt.Component users() {
        JTable table = Tables.create();
        Runnable load = () -> fillUsers(table);
        load.run();
        JButton create = Look.button("New");
        JButton password = Look.button("Password");
        JButton active = Look.button("Activate/deactivate");
        JButton admin = Look.button("Administrator");
        create.addActionListener(event -> {
            Connection connection = hub.connection(node.databaseId());
            if (connection != null && Dialogs.user(hub.owner(), connection)) {
                load.run();
                hub.refreshOutline(node.databaseId());
            }
        });
        password.addActionListener(event -> withUser(table, (connection, name) -> {
            if (Dialogs.changePassword(hub.owner(), connection, name)) {
                hub.message("password changed");
            }
        }));
        active.addActionListener(event -> withUser(table, (connection, name) -> {
            boolean on = !bool(Tables.row(table, table.getSelectedRow()).get(1));
            try (var statement = connection.prepareStatement(
                    "UPDATE NEX$USERS SET NEX$ACTIVE = ? WHERE NEX$USER_NAME = ?")) {
                statement.setBoolean(1, on);
                statement.setString(2, name);
                statement.executeUpdate();
            }
            load.run();
        }));
        admin.addActionListener(event -> withUser(table, (connection, name) -> {
            boolean on = !bool(Tables.row(table, table.getSelectedRow()).get(2));
            try (var statement = connection.prepareStatement(
                    "UPDATE NEX$USERS SET NEX$ADMIN = ? WHERE NEX$USER_NAME = ?")) {
                statement.setBoolean(1, on);
                statement.setString(2, name);
                statement.executeUpdate();
            }
            load.run();
            hub.refreshOutline(node.databaseId());
        }));
        JButton refresh = Look.button("Refresh");
        refresh.addActionListener(event -> load.run());
        return wrap(Look.scroll(table), buttons(create, password, active, admin, refresh));
    }

    private void fillUsers(JTable table) {
        Connection connection = hub.connection(node.databaseId());
        if (connection == null) {
            return;
        }
        try {
            List<List<Object>> rows = new ArrayList<>();
            for (Outline.UserInfo user : Outline.users(connection)) {
                rows.add(List.of(user.name(), user.active(), user.admin(), user.first(), user.last()));
            }
            Tables.show(table, List.of("User", "Active", "Admin", "Name", "Last name"), rows);
        } catch (SQLException exception) {
            Dialogs.error(hub.owner(), Dialogs.message(exception));
        }
    }

    private java.awt.Component connections() {
        JTable table = Tables.create();
        Runnable load = () -> {
            Connection connection = hub.connection(node.databaseId());
            if (connection == null) {
                return;
            }
            try {
                List<List<Object>> rows = new ArrayList<>();
                for (Outline.LinkInfo link : Outline.connections(connection)) {
                    rows.add(List.of(link.id(), link.user(), link.state(), link.connectedAt(),
                            link.protocol(), link.address(), link.host(), link.osUser(), link.process()));
                }
                Tables.show(table, List.of("Id", "User", "State", "Connected", "Protocol", "Address", "Host",
                        "OS user", "Process"), rows);
            } catch (SQLException exception) {
                Dialogs.error(hub.owner(), Dialogs.message(exception));
            }
        };
        load.run();
        JButton drop = Look.button("End");
        drop.addActionListener(event -> {
            if (table.getSelectedRow() < 0) {
                return;
            }
            Object id = Tables.row(table, table.getSelectedRow()).get(0);
            if (!Dialogs.confirm(hub.owner(), "End connection " + id + "?")) {
                return;
            }
            Connection connection = hub.connection(node.databaseId());
            if (connection == null) {
                return;
            }
            try {
                Commands.run(connection, "DROP CONNECTION " + id);
                load.run();
            } catch (SQLException exception) {
                Dialogs.error(hub.owner(), Dialogs.message(exception));
            }
        });
        JButton refresh = Look.button("Refresh");
        refresh.addActionListener(event -> load.run());
        return wrap(Look.scroll(table), buttons(refresh, drop));
    }

    private java.awt.Component privileges(String schema, String table) {
        JTable grid = Tables.create();
        Runnable load = () -> {
            Connection connection = hub.connection(node.databaseId());
            if (connection == null) {
                return;
            }
            try {
                fillGrants(grid, connection, table == null ? null : SqlNames.catalog(schema, table));
            } catch (SQLException exception) {
                Dialogs.error(hub.owner(), Dialogs.message(exception));
            }
        };
        load.run();
        JButton grant = Look.button("Grant");
        grant.addActionListener(event -> {
            Connection connection = hub.connection(node.databaseId());
            if (connection != null && Dialogs.grant(hub.owner(), connection, schema, table)) {
                load.run();
            }
        });
        JButton revoke = Look.button("Revoke");
        revoke.addActionListener(event -> {
            if (revoke(grid)) {
                load.run();
            }
        });
        return wrap(Look.scroll(grid), buttons(grant, revoke));
    }

    private java.awt.Component config() {
        JTable table = Tables.create();
        Runnable load = () -> {
            Connection connection = hub.connection(node.databaseId());
            if (connection == null) {
                return;
            }
            try {
                List<List<Object>> rows = new ArrayList<>();
                for (Outline.ConfigInfo item : Outline.config(connection)) {
                    rows.add(List.of(item.key(), item.value()));
                }
                Tables.show(table, List.of("Key", "Value"), rows);
            } catch (SQLException exception) {
                Dialogs.error(hub.owner(), Dialogs.message(exception));
            }
        };
        load.run();
        JButton edit = Look.button("Change value");
        edit.addActionListener(event -> {
            if (table.getSelectedRow() < 0) {
                return;
            }
            List<Object> row = Tables.row(table, table.getSelectedRow());
            String key = String.valueOf(row.get(0));
            String value = Dialogs.ask(hub.owner(), "Configuration", key, String.valueOf(row.get(1)));
            if (value == null) {
                return;
            }
            Connection connection = hub.connection(node.databaseId());
            if (connection == null) {
                return;
            }
            try (var statement = connection.prepareStatement("UPDATE NEX$CONFIG SET NEX$VALUE = ? WHERE NEX$KEY = ?")) {
                statement.setString(1, value);
                statement.setString(2, key);
                statement.executeUpdate();
                load.run();
            } catch (SQLException exception) {
                Dialogs.error(hub.owner(), Dialogs.message(exception));
            }
        });
        JButton refresh = Look.button("Refresh");
        refresh.addActionListener(event -> load.run());
        return wrap(Look.scroll(table), buttons(edit, refresh));
    }

    private java.awt.Component info() {
        JPanel panel = new JPanel(new java.awt.GridBagLayout());
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));
        Connection connection = hub.connection(node.databaseId());
        if (connection == null) {
            return new JLabel("Database disconnected.");
        }
        try {
            Outline.DatabaseInfo info = Outline.database(connection);
            String charset = "";
            for (Outline.ConfigInfo item : Outline.config(connection)) {
                if (item.key().equalsIgnoreCase("Default.CharacterSet")) {
                    charset = item.value();
                }
            }
            String[][] rows = {
                    {"Name", info.name()},
                    {"Description", info.description()},
                    {"Owner", info.owner()},
                    {"Created", info.created()},
                    {"Format", Integer.toString(info.format())},
                    {"Dialect", Integer.toString(info.dialect())},
                    {"Page", Integer.toString(info.pageSize())},
                    {"Charset", charset},
                    {"User", connection.getMetaData().getUserName()},
                    {"Schema", connection.getSchema()}
            };
            java.awt.GridBagConstraints constraints = new java.awt.GridBagConstraints();
            constraints.insets = new java.awt.Insets(4, 6, 4, 12);
            constraints.anchor = java.awt.GridBagConstraints.LINE_START;
            for (int i = 0; i < rows.length; i++) {
                constraints.gridy = i;
                constraints.gridx = 0;
                JLabel key = new JLabel(rows[i][0]);
                key.setForeground(Look.MUTED);
                panel.add(key, constraints);
                constraints.gridx = 1;
                panel.add(new JLabel(rows[i][1] == null ? "" : rows[i][1]), constraints);
            }
        } catch (SQLException exception) {
            return new JLabel(Dialogs.message(exception));
        }
        return panel;
    }

    private void reloadColumns(JTable table) {
        Connection connection = hub.connection(node.databaseId());
        if (connection == null) {
            return;
        }
        try {
            fillColumns(table, Outline.columns(connection, node.schema(), node.name()));
        } catch (SQLException exception) {
            Dialogs.error(hub.owner(), Dialogs.message(exception));
        }
    }

    private void reloadDdl(JTextArea ddl) {
        Connection connection = hub.connection(node.databaseId());
        if (connection == null) {
            return;
        }
        try {
            ddl.setText(script(connection));
            ddl.setCaretPosition(0);
        } catch (SQLException exception) {
            Dialogs.error(hub.owner(), Dialogs.message(exception));
        }
    }

    private void newIndex(JTable indexes, JTextArea ddl) {
        Connection connection = requireQuiet();
        if (connection == null) {
            return;
        }
        try {
            List<String> names = new ArrayList<>();
            for (Ddl.ColumnInfo column : Outline.columns(connection, node.schema(), node.name())) {
                names.add(column.name());
            }
            if (Dialogs.index(hub.owner(), connection, node.schema(), node.name(), names)) {
                fillIndexes(indexes, Outline.indexes(connection, node.schema(), node.name()));
                refreshConstraints(connection);
                reloadDdl(ddl);
                hub.refreshOutline(node.databaseId());
            }
        } catch (SQLException exception) {
            Dialogs.error(hub.owner(), Dialogs.message(exception));
        }
    }

    private void dropIndex(JTable indexes, JTextArea ddl) {
        if (indexes.getSelectedRow() < 0) {
            return;
        }
        String name = String.valueOf(Tables.row(indexes, indexes.getSelectedRow()).get(0));
        if (name.equalsIgnoreCase("PRIMARY") || Ddl.generatedUnique(name)) {
            Dialogs.error(hub.owner(), "This index belongs to a constraint. Delete the key on the Constraints tab.");
            return;
        }
        if (!Dialogs.confirm(hub.owner(), "Delete index " + name + "?")) {
            return;
        }
        Connection connection = requireQuiet();
        if (connection == null) {
            return;
        }
        try {
            Commands.run(connection, Ddl.dropIndex(node.schema(), node.name(), name));
            fillIndexes(indexes, Outline.indexes(connection, node.schema(), node.name()));
            refreshConstraints(connection);
            reloadDdl(ddl);
        } catch (SQLException exception) {
            Dialogs.error(hub.owner(), Dialogs.message(exception));
        }
    }

    private void dropConstraint(JTable keys, JTable columns, JTable indexes, JTextArea ddl) {
        if (keys.getSelectedRow() < 0) {
            return;
        }
        String name = String.valueOf(Tables.row(keys, keys.getSelectedRow()).get(0));
        if (!Dialogs.confirm(hub.owner(), "Delete constraint " + name + "?")) {
            return;
        }
        Connection connection = requireQuiet();
        if (connection == null) {
            return;
        }
        try {
            Commands.run(connection, Ddl.dropConstraint(node.schema(), node.name(), name));
            reloadColumns(columns);
            fillIndexes(indexes, Outline.indexes(connection, node.schema(), node.name()));
            refreshConstraints(connection);
            reloadDdl(ddl);
            if (data != null && data.loaded()) {
                data.load();
            }
            hub.refreshOutline(node.databaseId());
        } catch (SQLException exception) {
            Dialogs.error(hub.owner(), Dialogs.message(exception));
        }
    }

    private void dropColumn(JTable columns, JTextArea ddl) {
        if (columns.getSelectedRow() < 0) {
            return;
        }
        String name = String.valueOf(Tables.row(columns, columns.getSelectedRow()).get(0));
        if (!Dialogs.confirm(hub.owner(), "Delete column " + name + "?")) {
            return;
        }
        Connection connection = requireQuiet();
        if (connection == null) {
            return;
        }
        try {
            Commands.run(connection, Ddl.dropColumn(node.schema(), node.name(), name));
            reloadColumns(columns);
            refreshConstraints(connection);
            reloadDdl(ddl);
            reloadData();
            hub.refreshOutline(node.databaseId());
        } catch (SQLException exception) {
            Dialogs.error(hub.owner(), Dialogs.message(exception));
        }
    }

    private void moveColumn(JTable columns, JTextArea ddl, int delta) {
        int row = columns.getSelectedRow();
        if (row < 0) {
            return;
        }
        int target = row + delta;
        if (target < 0 || target >= columns.getRowCount()) {
            return;
        }
        String name = String.valueOf(Tables.row(columns, row).get(0));
        Connection connection = requireQuiet();
        if (connection == null) {
            return;
        }
        try {
            Commands.run(connection, Ddl.position(node.schema(), node.name(), name, target + 1));
            reloadColumns(columns);
            if (target < columns.getRowCount()) {
                columns.setRowSelectionInterval(target, target);
                columns.scrollRectToVisible(columns.getCellRect(target, 0, true));
            }
            reloadDdl(ddl);
            reloadData();
            hub.refreshOutline(node.databaseId());
        } catch (SQLException exception) {
            Dialogs.error(hub.owner(), Dialogs.message(exception));
        }
    }

    private void reloadData() {
        if (data != null && data.loaded()) {
            data.load();
        }
    }

    private boolean revoke(JTable grants) {
        if (grants.getSelectedRow() < 0) {
            return false;
        }
        List<Object> row = Tables.row(grants, grants.getSelectedRow());
        String user = String.valueOf(row.get(0));
        String privilege;
        String table;
        String schema = node.schema().isBlank() ? "public" : node.schema();
        if (row.size() > 4) {
            table = String.valueOf(row.get(1));
            privilege = String.valueOf(row.get(2));
            int dot = table.indexOf('.');
            if (dot > 0) {
                schema = table.substring(0, dot);
                table = table.substring(dot + 1);
            } else {
                schema = "public";
            }
        } else {
            privilege = String.valueOf(row.get(1));
            table = node.name();
        }
        if ("OWNER".equalsIgnoreCase(privilege)) {
            Dialogs.error(hub.owner(), "OWNER cannot be revoked. The owner stays.");
            return false;
        }
        if (!Dialogs.confirm(hub.owner(), "Revoke " + privilege + " from " + user + " on " + table + "?")) {
            return false;
        }
        Connection connection = requireQuiet();
        if (connection == null) {
            return false;
        }
        try {
            Commands.run(connection, Ddl.revoke(privilege, schema, table, user));
            return true;
        } catch (SQLException exception) {
            Dialogs.error(hub.owner(), Dialogs.message(exception));
            return false;
        }
    }

    private void reloadGrants(JTable grants) {
        Connection connection = hub.connection(node.databaseId());
        if (connection == null) {
            return;
        }
        try {
            fillGrants(grants, connection, SqlNames.catalog(node.schema(), node.name()));
        } catch (SQLException exception) {
            Dialogs.error(hub.owner(), Dialogs.message(exception));
        }
    }

    private void fillGrants(JTable table, Connection connection, String catalog) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        boolean global = catalog == null;
        for (Outline.GrantInfo grant : Outline.grants(connection)) {
            if (!global && !SqlNames.same(grant.table(), catalog)) {
                continue;
            }
            if (global) {
                rows.add(List.of(grant.user(), grant.table(), grant.privilege(), grant.grantOption() ? "yes" : "no", grant.grantor()));
            } else {
                rows.add(List.of(grant.user(), grant.privilege(), grant.grantOption() ? "yes" : "no", grant.grantor()));
            }
        }
        if (global) {
            Tables.show(table, List.of("User", "Table", "Privilege", "Grant option", "Granted by"), rows);
        } else {
            Tables.show(table, List.of("User", "Privilege", "Grant option", "Granted by"), rows);
        }
    }

    private void refreshConstraints(Connection connection) throws SQLException {
        if (primaryKeys == null) {
            return;
        }
        List<Ddl.IndexInfo> indexes = Outline.indexes(connection, node.schema(), node.name());
        List<Ddl.ConstraintInfo> primary = Outline.primaryKeys(connection, node.schema(), node.name(), indexes);
        List<Ddl.ConstraintInfo> foreign = Outline.foreignKeys(connection, node.schema(), node.name(), indexes);
        fillKeys(primaryKeys, primary, false);
        fillKeys(foreignKeys, foreign, true);
        fillKeys(uniqueKeys, Outline.uniques(indexes, primary), false);
    }

    private String script(Connection connection) throws SQLException {
        List<Ddl.IndexInfo> indexes = Outline.indexes(connection, node.schema(), node.name());
        List<Ddl.ConstraintInfo> primary = Outline.primaryKeys(connection, node.schema(), node.name(), indexes);
        return Ddl.table(node.schema(), node.name(),
                Outline.columns(connection, node.schema(), node.name()),
                indexes,
                primary.isEmpty() ? null : primary.get(0),
                Outline.foreignKeys(connection, node.schema(), node.name(), indexes));
    }

    private static String rule(String value) {
        return value == null || value.isBlank() ? "NO ACTION" : value;
    }

    private static void fillKeys(JTable table, List<Ddl.ConstraintInfo> keys, boolean reference) {
        List<List<Object>> rows = new ArrayList<>();
        for (Ddl.ConstraintInfo key : keys) {
            String sorting = key.descending() ? "Descending" : "Ascending";
            if (reference) {
                String target = key.referencedSchema() == null || SqlNames.same(key.referencedSchema(), "public")
                        ? key.referencedTable()
                        : key.referencedSchema() + "." + key.referencedTable();
                rows.add(List.of(key.name(), String.join(", ", key.columns()),
                        target + " (" + String.join(", ", key.referencedColumns()) + ")",
                        rule(key.onUpdate()), rule(key.onDelete()),
                        key.index(), sorting));
            } else {
                rows.add(List.of(key.name(), String.join(", ", key.columns()), key.index(), sorting));
            }
        }
        if (reference) {
            Tables.show(table, List.of("Constraint Name", "On Field", "Reference", "Update Rule", "Delete Rule",
                    "Index Name", "Index Sorting"), rows);
        } else {
            Tables.show(table, List.of("Constraint Name", "On Field", "Index Name", "Index Sorting"), rows);
        }
    }

    private static void fillColumns(JTable table, List<Ddl.ColumnInfo> columns) {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            Ddl.ColumnInfo column = columns.get(i);
            rows.add(List.of(column.name(), i + 1, Ddl.typeSql(column.typeName(), column.size(), column.scale()),
                    column.nullable() ? "yes" : "no",
                    column.primaryKeyPosition() == 0 ? "" : Integer.toString(column.primaryKeyPosition()),
                    column.autoIncrement() ? "yes" : "",
                    column.defaultSql() == null ? "" : column.defaultSql()));
        }
        Tables.show(table, List.of("Name", "Position", "Type", "Null", "PK", "Auto", "Default"), rows);
    }

    private static void fillIndexes(JTable table, List<Ddl.IndexInfo> indexes) {
        List<List<Object>> rows = new ArrayList<>();
        for (Ddl.IndexInfo index : indexes) {
            rows.add(List.of(index.name(), index.unique() ? "yes" : "no", String.join(", ", index.columns())));
        }
        Tables.show(table, List.of("Name", "Unique", "Columns"), rows);
    }

    private void withUser(JTable table, UserAction action) {
        if (table.getSelectedRow() < 0) {
            Dialogs.error(hub.owner(), "select a user");
            return;
        }
        Connection connection = hub.connection(node.databaseId());
        if (connection == null) {
            return;
        }
        try {
            action.run(connection, String.valueOf(Tables.row(table, table.getSelectedRow()).get(0)));
        } catch (SQLException exception) {
            Dialogs.error(hub.owner(), Dialogs.message(exception));
        }
    }

    private Connection requireQuiet() {
        Connection connection = hub.connection(node.databaseId());
        if (connection == null) {
            Dialogs.error(hub.owner(), "database disconnected");
        }
        return connection;
    }

    private static Outline.TriggerInfo findTrigger(Connection connection, String name) throws SQLException {
        for (Outline.TriggerInfo trigger : Outline.triggers(connection)) {
            if (SqlNames.same(trigger.name(), name)) {
                return trigger;
            }
        }
        throw new SQLException("trigger not found: " + name);
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(String.valueOf(value)) || "yes".equalsIgnoreCase(String.valueOf(value));
    }

    private static JTextArea scriptArea() {
        JTextArea area = new JTextArea();
        area.setFont(Look.MONO);
        area.setEditable(false);
        area.setBackground(Look.EDITOR);
        return area;
    }

    private static javax.swing.JScrollPane scriptScroll(JTextArea area) {
        return Look.scroll(area);
    }

    private static JPanel wrap(java.awt.Component center, JPanel actions) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(actions, BorderLayout.NORTH);
        panel.add(center, BorderLayout.CENTER);
        return panel;
    }

    private static JPanel buttons(JButton... buttons) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        Look.band(panel);
        for (JButton button : buttons) {
            if (button != null) {
                panel.add(button);
            }
        }
        return panel;
    }

    private JButton button(String text, Runnable action, boolean enabled) {
        JButton button = Look.button(text);
        button.setEnabled(enabled);
        button.addActionListener(event -> action.run());
        return button;
    }

    private static void copy(String text) {
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(text), null);
    }

    @FunctionalInterface
    private interface UserAction {
        void run(Connection connection, String name) throws SQLException;
    }
}
