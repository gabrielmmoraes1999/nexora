package io.github.gabrielmmoraes1999.nexora.studio;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
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
        panel.setBorder(new EmptyBorder(8, 12, 8, 12));
        JLabel title = new JLabel(caption());
        title.setFont(Look.UI_BOLD);
        title.setForeground(Look.NAVY);
        panel.add(title, BorderLayout.WEST);
        return panel;
    }

    private String caption() {
        String kind = switch (node.kind()) {
            case TABLE -> "Tabela";
            case SYSTEM_TABLE -> "Tabela de sistema";
            case VIEW -> "Visão";
            case PROCEDURE -> "Procedure";
            case TRIGGER -> "Trigger";
            case GENERATOR -> "Gerador";
            case ROLE -> "Papel";
            case USER, FOLDER -> "Usuários";
            case CONNECTIONS -> "Conexões";
            case PRIVILEGES -> "Privilégios";
            case CONFIG -> "Configuração";
            case INFO -> "Propriedades";
            default -> "Objeto";
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
            default -> new JLabel("Nada para abrir neste item.");
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
        tabs.addTab("Colunas", wrap(columns, buttons(
                button("Nova coluna", () -> {
                    Connection connection = requireQuiet();
                    if (connection != null && Dialogs.column(hub.owner(), connection, node.schema(), node.name())) {
                        hub.refreshOutline(node.databaseId());
                        reloadColumns(columns);
                        reloadDdl(ddl);
                    }
                }, editableShape),
                button("Excluir coluna", () -> dropColumn(columns, ddl), editableShape))));
        tabs.addTab("Dados", data);
        tabs.addTab("Índices", wrap(indexes, buttons(
                button("Novo índice", () -> newIndex(indexes, ddl), editableShape),
                button("Excluir índice", () -> dropIndex(indexes, ddl), editableShape))));
        tabs.addTab("Triggers", Look.scroll(triggers));
        tabs.addTab("Privilégios", wrap(grants, buttons(
                button("Conceder", () -> {
                    if (Dialogs.grant(hub.owner(), requireQuiet(), node.schema(), node.name())) {
                        reloadGrants(grants);
                    }
                }, true),
                button("Revogar", () -> revoke(grants), true))));
        tabs.addTab("DDL", wrap(scriptScroll(ddl), buttons(
                button("Copiar", () -> copy(ddl.getText()), true),
                button("Abrir no editor", () -> hub.openSql(node.databaseId(), "DDL " + node.name(), ddl.getText()), true))));
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
            String catalog = SqlNames.catalog(node.schema(), node.name());
            List<List<Object>> triggerRows = new ArrayList<>();
            for (Outline.TriggerInfo trigger : Outline.triggers(connection)) {
                if (SqlNames.same(trigger.relation(), catalog) || SqlNames.same(trigger.relation(), node.name())) {
                    triggerRows.add(List.of(trigger.name(), trigger.type(), trigger.position(),
                            trigger.inactive() ? "não" : "sim", trigger.owner()));
                }
            }
            Tables.show(triggers, List.of("Nome", "Quando", "Posição", "Ativo", "Dono"), triggerRows);
            fillGrants(grants, connection, catalog);
            ddl.setText(Ddl.table(node.schema(), node.name(),
                    Outline.columns(connection, node.schema(), node.name()),
                    Outline.indexes(connection, node.schema(), node.name())));
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
        tabs.addTab("Código", wrap(scriptScroll(source), buttons(
                button("Abrir no editor", () -> hub.openSql(node.databaseId(), node.name(), source.getText()), true))));
        tabs.addTab("Dados", data);
        tabs.addTab("DDL", wrap(scriptScroll(ddl), buttons(
                button("Copiar", () -> copy(ddl.getText()), true))));
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
                Outline.Routine routine = Outline.procedure(connection, node.name());
                source.setText(Ddl.procedure(routine.name(), routine.parameters(), routine.source()));
                source.setCaretPosition(0);
                List<List<Object>> rows = new ArrayList<>();
                for (Ddl.Field field : routine.parameters()) {
                    rows.add(List.of(field.input() ? "entrada" : "saída", field.name(),
                            Ddl.typeSql(field.typeName(), field.size(), field.scale())));
                }
                Tables.show(parameters, List.of("Direção", "Nome", "Tipo"), rows);
            } catch (SQLException exception) {
                Dialogs.error(hub.owner(), Dialogs.message(exception));
            }
        }
        JTabbedPane pages = new JTabbedPane();
        pages.addTab("Código", wrap(scriptScroll(source), buttons(
                button("Abrir no editor", () -> hub.openSql(node.databaseId(), node.name(), source.getText()), true),
                button("Copiar", () -> copy(source.getText()), true))));
        pages.addTab("Parâmetros", Look.scroll(parameters));
        return pages;
    }

    private java.awt.Component trigger() {
        JTextArea source = scriptArea();
        JLabel state = new JLabel(" ");
        JButton toggle = Look.button("Ativar");
        Connection connection = hub.connection(node.databaseId());
        if (connection != null) {
            try {
                Outline.TriggerInfo trigger = findTrigger(connection, node.name());
                source.setText(Ddl.trigger(trigger.name(), trigger.relation(), trigger.inactive(), trigger.type(),
                        trigger.position(), trigger.source()));
                source.setCaretPosition(0);
                state.setText(trigger.inactive() ? "Inativo" : "Ativo");
                toggle.setText(trigger.inactive() ? "Ativar" : "Desativar");
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
                        toggle.setText(inactive[0] ? "Ativar" : "Desativar");
                        state.setText(inactive[0] ? "Inativo" : "Ativo");
                        hub.refreshOutline(node.databaseId());
                        hub.message(inactive[0] ? "trigger desativado" : "trigger ativado");
                    } catch (SQLException exception) {
                        Dialogs.error(hub.owner(), Dialogs.message(exception));
                    }
                });
            } catch (SQLException exception) {
                Dialogs.error(hub.owner(), Dialogs.message(exception));
            }
        }
        return wrap(scriptScroll(source), buttons(toggle,
                button("Abrir no editor", () -> hub.openSql(node.databaseId(), node.name(), source.getText()), true)));
    }

    private java.awt.Component generator() {
        JPanel panel = new JPanel(new java.awt.GridBagLayout());
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));
        panel.setOpaque(false);
        JLabel current = new JLabel("—");
        current.setFont(Look.UI_BOLD);
        JTextField value = new JTextField(12);
        JButton apply = Look.primary("Definir valor");
        Connection connection = hub.connection(node.databaseId());
        if (connection != null) {
            try {
                Outline.GeneratorInfo generator = Outline.generator(connection, node.name());
                current.setText(Long.toString(generator.current()));
                apply.addActionListener(event -> {
                    Connection live = hub.connection(node.databaseId());
                    if (live == null) {
                        return;
                    }
                    try {
                        long next = Long.parseLong(value.getText().trim());
                        Commands.run(live, "SET GENERATOR " + SqlNames.quote(node.name()) + " TO " + next);
                        current.setText(Long.toString(Outline.generator(live, node.name()).current()));
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
                panel.add(new JLabel("Valor atual"), constraints);
                constraints.gridx = 1;
                panel.add(current, constraints);
                constraints.gridy = 1;
                constraints.gridx = 0;
                panel.add(new JLabel("Início " + generator.initial() + "   passo " + generator.increment()
                        + "   dono " + generator.owner()), constraints);
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
                        rows.add(List.of(member.user(), member.adminOption() ? "sim" : "não", member.grantor()));
                    }
                }
                Tables.show(members, List.of("Usuário", "Admin", "Concedido por"), rows);
            } catch (SQLException exception) {
                Dialogs.error(hub.owner(), Dialogs.message(exception));
            }
        };
        load.run();
        JButton grant = Look.button("Conceder a usuário");
        grant.addActionListener(event -> {
            String user = Dialogs.ask(hub.owner(), "Conceder papel", "Usuário ou PUBLIC", "");
            if (user == null) {
                return;
            }
            int choice = javax.swing.JOptionPane.showConfirmDialog(hub.owner(),
                    "Conceder ADMIN OPTION para " + user + "?", "Papel",
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
        JButton create = Look.button("Novo");
        JButton password = Look.button("Senha");
        JButton active = Look.button("Ativar/desativar");
        JButton admin = Look.button("Administrador");
        create.addActionListener(event -> {
            Connection connection = hub.connection(node.databaseId());
            if (connection != null && Dialogs.user(hub.owner(), connection)) {
                load.run();
                hub.refreshOutline(node.databaseId());
            }
        });
        password.addActionListener(event -> withUser(table, (connection, name) -> {
            if (Dialogs.changePassword(hub.owner(), connection, name)) {
                hub.message("senha alterada");
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
        JButton refresh = Look.button("Atualizar");
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
            Tables.show(table, List.of("Usuário", "Ativo", "Admin", "Nome", "Sobrenome"), rows);
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
                            link.disconnectedAt(), link.process()));
                }
                Tables.show(table, List.of("Id", "Usuário", "Estado", "Entrada", "Saída", "Processo"), rows);
            } catch (SQLException exception) {
                Dialogs.error(hub.owner(), Dialogs.message(exception));
            }
        };
        load.run();
        JButton drop = Look.button("Encerrar");
        drop.addActionListener(event -> {
            if (table.getSelectedRow() < 0) {
                return;
            }
            Object id = Tables.row(table, table.getSelectedRow()).get(0);
            if (!Dialogs.confirm(hub.owner(), "Encerrar a conexão " + id + "?")) {
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
        JButton refresh = Look.button("Atualizar");
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
        JButton grant = Look.button("Conceder");
        grant.addActionListener(event -> {
            Connection connection = hub.connection(node.databaseId());
            if (connection != null && Dialogs.grant(hub.owner(), connection, schema, table)) {
                load.run();
            }
        });
        JButton revoke = Look.button("Revogar");
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
                Tables.show(table, List.of("Chave", "Valor"), rows);
            } catch (SQLException exception) {
                Dialogs.error(hub.owner(), Dialogs.message(exception));
            }
        };
        load.run();
        JButton edit = Look.button("Alterar valor");
        edit.addActionListener(event -> {
            if (table.getSelectedRow() < 0) {
                return;
            }
            List<Object> row = Tables.row(table, table.getSelectedRow());
            String key = String.valueOf(row.get(0));
            String value = Dialogs.ask(hub.owner(), "Configuração", key, String.valueOf(row.get(1)));
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
        JButton refresh = Look.button("Atualizar");
        refresh.addActionListener(event -> load.run());
        return wrap(Look.scroll(table), buttons(edit, refresh));
    }

    private java.awt.Component info() {
        JPanel panel = new JPanel(new java.awt.GridBagLayout());
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));
        Connection connection = hub.connection(node.databaseId());
        if (connection == null) {
            return new JLabel("Banco desconectado.");
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
                    {"Nome", info.name()},
                    {"Descrição", info.description()},
                    {"Dono", info.owner()},
                    {"Criado", info.created()},
                    {"Formato", Integer.toString(info.format())},
                    {"Dialeto", Integer.toString(info.dialect())},
                    {"Página", Integer.toString(info.pageSize())},
                    {"Charset", charset},
                    {"Usuário", connection.getMetaData().getUserName()},
                    {"Esquema", connection.getSchema()}
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
            ddl.setText(Ddl.table(node.schema(), node.name(),
                    Outline.columns(connection, node.schema(), node.name()),
                    Outline.indexes(connection, node.schema(), node.name())));
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
        if (name.equalsIgnoreCase("PRIMARY") || name.toLowerCase(java.util.Locale.ROOT).startsWith("unique_")) {
            Dialogs.error(hub.owner(), "PRIMARY e UNIQUE nascem com a tabela. Remova a coluna ou recrie a tabela.");
            return;
        }
        if (!Dialogs.confirm(hub.owner(), "Excluir o índice " + name + "?")) {
            return;
        }
        Connection connection = requireQuiet();
        if (connection == null) {
            return;
        }
        try {
            Commands.run(connection, Ddl.dropIndex(node.schema(), node.name(), name));
            fillIndexes(indexes, Outline.indexes(connection, node.schema(), node.name()));
            reloadDdl(ddl);
        } catch (SQLException exception) {
            Dialogs.error(hub.owner(), Dialogs.message(exception));
        }
    }

    private void dropColumn(JTable columns, JTextArea ddl) {
        if (columns.getSelectedRow() < 0) {
            return;
        }
        String name = String.valueOf(Tables.row(columns, columns.getSelectedRow()).get(0));
        if (!Dialogs.confirm(hub.owner(), "Excluir a coluna " + name + "?")) {
            return;
        }
        Connection connection = requireQuiet();
        if (connection == null) {
            return;
        }
        try {
            Commands.run(connection, Ddl.dropColumn(node.schema(), node.name(), name));
            reloadColumns(columns);
            reloadDdl(ddl);
            hub.refreshOutline(node.databaseId());
        } catch (SQLException exception) {
            Dialogs.error(hub.owner(), Dialogs.message(exception));
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
            Dialogs.error(hub.owner(), "OWNER não se revoga. O dono permanece.");
            return false;
        }
        if (!Dialogs.confirm(hub.owner(), "Revogar " + privilege + " de " + user + " em " + table + "?")) {
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
                rows.add(List.of(grant.user(), grant.table(), grant.privilege(), grant.grantOption() ? "sim" : "não", grant.grantor()));
            } else {
                rows.add(List.of(grant.user(), grant.privilege(), grant.grantOption() ? "sim" : "não", grant.grantor()));
            }
        }
        if (global) {
            Tables.show(table, List.of("Usuário", "Tabela", "Privilégio", "Repasse", "Concedido por"), rows);
        } else {
            Tables.show(table, List.of("Usuário", "Privilégio", "Repasse", "Concedido por"), rows);
        }
    }

    private static void fillColumns(JTable table, List<Ddl.ColumnInfo> columns) {
        List<List<Object>> rows = new ArrayList<>();
        for (Ddl.ColumnInfo column : columns) {
            rows.add(List.of(column.name(), Ddl.typeSql(column.typeName(), column.size(), column.scale()),
                    column.nullable() ? "sim" : "não",
                    column.primaryKeyPosition() == 0 ? "" : Integer.toString(column.primaryKeyPosition()),
                    column.autoIncrement() ? "sim" : "",
                    column.defaultSql() == null ? "" : column.defaultSql()));
        }
        Tables.show(table, List.of("Nome", "Tipo", "Nulo", "PK", "Auto", "Default"), rows);
    }

    private static void fillIndexes(JTable table, List<Ddl.IndexInfo> indexes) {
        List<List<Object>> rows = new ArrayList<>();
        for (Ddl.IndexInfo index : indexes) {
            rows.add(List.of(index.name(), index.unique() ? "sim" : "não", String.join(", ", index.columns())));
        }
        Tables.show(table, List.of("Nome", "Único", "Colunas"), rows);
    }

    private void withUser(JTable table, UserAction action) {
        if (table.getSelectedRow() < 0) {
            Dialogs.error(hub.owner(), "escolha um usuário");
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
            Dialogs.error(hub.owner(), "banco desconectado");
        }
        return connection;
    }

    private static Outline.TriggerInfo findTrigger(Connection connection, String name) throws SQLException {
        for (Outline.TriggerInfo trigger : Outline.triggers(connection)) {
            if (SqlNames.same(trigger.name(), name)) {
                return trigger;
            }
        }
        throw new SQLException("trigger não encontrado: " + name);
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(String.valueOf(value)) || "sim".equalsIgnoreCase(String.valueOf(value));
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
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        panel.setBackground(new java.awt.Color(0xF7F8FA));
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
