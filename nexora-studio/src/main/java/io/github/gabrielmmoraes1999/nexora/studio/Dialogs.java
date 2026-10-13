package io.github.gabrielmmoraes1999.nexora.studio;

import io.github.gabrielmmoraes1999.nexora.Database;
import io.github.gabrielmmoraes1999.nexora.DatabaseOptions;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

final class Dialogs {

    private Dialogs() {
    }

    record CreatedDatabase(DatabaseEntry entry, boolean connect) {
    }

    record Named(String schema, String name) {
    }

    static void error(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message == null || message.isBlank() ? "falha sem mensagem" : message,
                "Nexora Studio", JOptionPane.ERROR_MESSAGE);
    }

    static boolean confirm(Component parent, String message) {
        return JOptionPane.showConfirmDialog(parent, message, "Nexora Studio",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    static String message(Throwable error) {
        if (error == null) {
            return "falha sem mensagem";
        }
        if (error instanceof SQLException sql) {
            String text = sql.getMessage() == null ? sql.toString() : sql.getMessage();
            if (sql.getSQLState() != null && !sql.getSQLState().isBlank()) {
                return text + " (" + sql.getSQLState() + ")";
            }
            return text;
        }
        String text = error.getMessage();
        return text == null || text.isBlank() ? error.toString() : text;
    }

    static String ask(Component parent, String title, String label, String initial) {
        Object value = JOptionPane.showInputDialog(parent, label, title, JOptionPane.PLAIN_MESSAGE, null, null,
                initial == null ? "" : initial);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    static String password(Component parent, String alias) {
        JPasswordField field = new JPasswordField(18);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("Senha de " + alias), BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        JOptionPane option = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        JDialog dialog = option.createDialog(parent, "Conectar");
        dialog.setVisible(true);
        Object result = option.getValue();
        if (!(result instanceof Integer choice) || choice != JOptionPane.OK_OPTION) {
            return null;
        }
        return new String(field.getPassword());
    }

    static DatabaseEntry registration(Component parent, DatabaseEntry existing) {
        boolean editing = existing != null;
        JTextField alias = new JTextField(editing ? existing.alias() : "", 22);
        JRadioButton file = new JRadioButton("Arquivo .db", existing == null || existing.embedded());
        JRadioButton network = new JRadioButton("Rede (motor)", existing != null && !existing.embedded());
        ButtonGroup group = new ButtonGroup();
        group.add(file);
        group.add(network);
        JTextField path = new JTextField(editing && existing.embedded() ? existing.path() : "", 22);
        JButton browse = Look.button("...");
        JTextField host = new JTextField(editing ? existing.host() : "localhost", 16);
        JTextField port = new JTextField(editing ? Integer.toString(existing.port()) : "4580", 6);
        JTextField database = new JTextField(editing ? existing.database() : "", 16);
        JTextField user = new JTextField(editing ? existing.user() : "DBA", 16);
        JPasswordField password = new JPasswordField(editing ? existing.password() : "", 16);
        JTextField role = new JTextField(editing ? existing.role() : "", 16);
        JCheckBox remember = new JCheckBox("Lembrar senha neste computador", existing == null || existing.rememberPassword());
        browse.addActionListener(event -> {
            Path chosen = chooseFile(parent, false);
            if (chosen != null) {
                path.setText(chosen.toString());
                if (alias.getText().isBlank()) {
                    alias.setText(fileName(chosen));
                }
            }
        });
        Runnable mode = () -> {
            boolean embedded = file.isSelected();
            path.setEnabled(embedded);
            browse.setEnabled(embedded);
            host.setEnabled(!embedded);
            port.setEnabled(!embedded);
            database.setEnabled(!embedded);
        };
        file.addActionListener(event -> mode.run());
        network.addActionListener(event -> mode.run());
        mode.run();

        Form form = new Form();
        form.add("Apelido", alias);
        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        modeRow.setOpaque(false);
        modeRow.add(file);
        modeRow.add(network);
        form.add("Conexão", modeRow);
        form.add("Arquivo", row(path, browse));
        form.add("Host", host);
        form.add("Porta", port);
        form.add("Banco", database);
        form.add("Usuário", user);
        form.add("Senha", password);
        form.add("Papel", role);
        form.span(remember);
        JLabel hint = new JLabel("O arquivo já precisa existir. Para criar um .db, use Criar banco.");
        hint.setFont(Look.UI);
        hint.setForeground(Look.MUTED);
        form.span(hint);

        if (!open(parent, editing ? "Banco" : "Registrar banco", form.panel, null)) {
            return null;
        }
        try {
            int parsedPort = parsePort(port.getText());
            String aliasText = alias.getText().trim();
            boolean embedded = file.isSelected();
            if (embedded && path.getText().isBlank()) {
                throw new IllegalArgumentException("informe o arquivo .db");
            }
            if (!embedded && database.getText().isBlank()) {
                throw new IllegalArgumentException("informe o nome do banco no motor");
            }
            if (user.getText().isBlank()) {
                throw new IllegalArgumentException("informe o usuário");
            }
            String storedPath = embedded ? Path.of(path.getText().trim()).toAbsolutePath().normalize().toString() : "";
            if (aliasText.isBlank()) {
                aliasText = embedded ? fileName(Path.of(storedPath)) : database.getText().trim();
            }
            return new DatabaseEntry(
                    editing ? existing.id() : null,
                    aliasText,
                    embedded,
                    storedPath,
                    host.getText().trim(),
                    parsedPort,
                    database.getText().trim(),
                    user.getText().trim(),
                    new String(password.getPassword()),
                    role.getText().trim(),
                    remember.isSelected());
        } catch (RuntimeException exception) {
            error(parent, message(exception));
            return registration(parent, existing);
        }
    }

    static CreatedDatabase createDatabase(Component parent) {
        JTextField path = new JTextField(22);
        JButton browse = Look.button("...");
        JComboBox<String> page = new JComboBox<>(new String[]{"4096", "8192", "16384", "32768"});
        page.setSelectedItem("8192");
        JTextField user = new JTextField("DBA", 16);
        JPasswordField password = new JPasswordField("dba", 16);
        JTextField name = new JTextField(16);
        JTextField description = new JTextField("Nexora", 16);
        JTextField charset = new JTextField("UTF8", 16);
        JTextField alias = new JTextField(16);
        JCheckBox connect = new JCheckBox("Conectar agora", true);
        JCheckBox remember = new JCheckBox("Lembrar senha neste computador", true);
        browse.addActionListener(event -> {
            Path chosen = chooseFile(parent, true);
            if (chosen != null) {
                String text = chosen.toString();
                if (!text.toLowerCase(java.util.Locale.ROOT).endsWith(".db")) {
                    text = text + ".db";
                }
                path.setText(text);
                if (alias.getText().isBlank()) {
                    alias.setText(fileName(Path.of(text)));
                }
                if (name.getText().isBlank()) {
                    name.setText(stripExtension(fileName(Path.of(text))));
                }
            }
        });
        Form form = new Form();
        form.add("Arquivo", row(path, browse));
        form.add("Página", page);
        form.add("Usuário DBA", user);
        form.add("Senha", password);
        form.add("Nome", name);
        form.add("Descrição", description);
        form.add("Charset", charset);
        form.add("Apelido", alias);
        form.span(connect);
        form.span(remember);
        if (!open(parent, "Criar banco", form.panel, null)) {
            return null;
        }
        try {
            if (path.getText().isBlank()) {
                throw new IllegalArgumentException("informe o arquivo .db");
            }
            Path file = Path.of(path.getText().trim()).toAbsolutePath().normalize();
            String databaseName = name.getText().isBlank() ? stripExtension(fileName(file)) : name.getText().trim();
            String aliasText = alias.getText().isBlank() ? fileName(file) : alias.getText().trim();
            int pageSize = Integer.parseInt(String.valueOf(page.getSelectedItem()));
            DatabaseOptions options = new DatabaseOptions(pageSize, user.getText().trim(), new String(password.getPassword()),
                    databaseName, description.getText().trim(), charset.getText().trim());
            Database.create(file, options);
            DatabaseEntry entry = new DatabaseEntry(null, aliasText, true, file.toString(), "localhost", 4580, "",
                    options.user(), options.password(), "", remember.isSelected());
            return new CreatedDatabase(entry, connect.isSelected());
        } catch (RuntimeException exception) {
            error(parent, message(exception));
            return null;
        }
    }

    static Named table(Component parent, Connection connection, List<String> schemas, String current) {
        JComboBox<String> schema = new JComboBox<>(schemas.toArray(String[]::new));
        if (current != null) {
            schema.setSelectedItem(current);
        }
        JTextField name = new JTextField(16);
        String[] types = {"SMALLINT", "INTEGER", "BIGINT", "FLOAT", "DOUBLE", "DECIMAL", "BOOLEAN",
                "CHAR", "VARCHAR", "DATE", "TIME", "TIMESTAMP", "BLOB"};
        DefaultTableModel model = new DefaultTableModel(
                new Object[]{"Nome", "Tipo", "Tamanho", "Escala", "Nulo", "PK", "Único", "Auto", "Default"}, 0) {
            @Override
            public Class<?> getColumnClass(int column) {
                return switch (column) {
                    case 2, 3 -> Integer.class;
                    case 4, 5, 6, 7 -> Boolean.class;
                    default -> String.class;
                };
            }
        };
        model.addRow(new Object[]{"id", "INTEGER", 0, 0, false, true, false, true, ""});
        model.addRow(new Object[]{"nome", "VARCHAR", 120, 0, false, false, false, false, ""});
        JTable grid = Tables.create();
        grid.setModel(model);
        Tables.install(grid);
        grid.getColumnModel().getColumn(1).setCellEditor(new javax.swing.DefaultCellEditor(new JComboBox<>(types)));
        grid.setPreferredScrollableViewportSize(new Dimension(680, 220));
        JButton add = Look.button("Coluna");
        JButton remove = Look.button("Remover");
        add.addActionListener(event -> model.addRow(new Object[]{"coluna", "VARCHAR", 80, 0, true, false, false, false, ""}));
        remove.addActionListener(event -> {
            int row = grid.getSelectedRow();
            if (row >= 0) {
                model.removeRow(grid.convertRowIndexToModel(row));
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(add);
        buttons.add(remove);
        JLabel hint = new JLabel("Default é SQL: 0, 'texto', TRUE. A chave pode ser uma coluna ou várias marcadas como PK.");
        hint.setForeground(Look.MUTED);
        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setBorder(new EmptyBorder(8, 0, 0, 0));
        center.setOpaque(false);
        center.add(buttons, BorderLayout.NORTH);
        center.add(Look.scroll(grid), BorderLayout.CENTER);
        center.add(hint, BorderLayout.SOUTH);
        Form form = new Form();
        form.add("Esquema", schema);
        form.add("Tabela", name);
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));
        panel.add(form.panel, BorderLayout.NORTH);
        panel.add(center, BorderLayout.CENTER);
        final Named[] created = {null};
        JDialog dialog = dialog(parent, "Nova tabela", panel);
        JButton ok = Look.primary("Criar");
        JButton cancel = Look.button("Cancelar");
        ok.addActionListener(event -> {
            if (grid.isEditing()) {
                grid.getCellEditor().stopCellEditing();
            }
            try {
                List<Ddl.ColumnDraft> columns = new ArrayList<>();
                for (int row = 0; row < model.getRowCount(); row++) {
                    columns.add(new Ddl.ColumnDraft(
                            text(model.getValueAt(row, 0)),
                            text(model.getValueAt(row, 1)),
                            number(model.getValueAt(row, 2)),
                            number(model.getValueAt(row, 3)),
                            bool(model.getValueAt(row, 4)),
                            bool(model.getValueAt(row, 5)),
                            bool(model.getValueAt(row, 6)),
                            bool(model.getValueAt(row, 7)),
                            text(model.getValueAt(row, 8))));
                }
                String schemaName = String.valueOf(schema.getSelectedItem());
                String tableName = name.getText().trim();
                Commands.run(connection, Ddl.create(schemaName, tableName, columns));
                created[0] = new Named(schemaName, tableName);
                dialog.dispose();
            } catch (RuntimeException | SQLException exception) {
                error(dialog, message(exception));
            }
        });
        cancel.addActionListener(event -> dialog.dispose());
        dialog.add(actions(ok, cancel), BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(ok);
        show(dialog);
        return created[0];
    }

    static boolean index(Component parent, Connection connection, String schema, String table, List<String> columns) {
        JTextField name = new JTextField(16);
        JCheckBox unique = new JCheckBox("Único");
        JPanel list = new JPanel(new GridBagLayout());
        list.setOpaque(false);
        List<JCheckBox> boxes = new ArrayList<>();
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.anchor = GridBagConstraints.LINE_START;
        constraints.gridx = 0;
        int row = 0;
        for (String column : columns) {
            JCheckBox box = new JCheckBox(column);
            boxes.add(box);
            constraints.gridy = row++;
            list.add(box, constraints);
        }
        Form form = new Form();
        form.add("Nome", name);
        form.span(unique);
        form.span(new JLabel("Colunas"));
        form.span(new JScrollPane(list));
        return open(parent, "Novo índice em " + table, form.panel, () -> {
            try {
                List<String> chosen = new ArrayList<>();
                for (JCheckBox box : boxes) {
                    if (box.isSelected()) {
                        chosen.add(box.getText());
                    }
                }
                Commands.run(connection, Ddl.createIndex(unique.isSelected(), name.getText(), schema, table, chosen));
                return true;
            } catch (RuntimeException | SQLException exception) {
                error(parent, message(exception));
                return false;
            }
        });
    }

    static boolean column(Component parent, Connection connection, String schema, String table) {
        JTextField name = new JTextField(16);
        JComboBox<String> type = new JComboBox<>(new String[]{
                "SMALLINT", "INTEGER", "BIGINT", "FLOAT", "DOUBLE", "DECIMAL", "BOOLEAN",
                "CHAR", "VARCHAR", "DATE", "TIME", "TIMESTAMP", "BLOB"});
        type.setSelectedItem("VARCHAR");
        JTextField size = new JTextField("80", 6);
        JTextField scale = new JTextField("0", 4);
        JCheckBox nullable = new JCheckBox("Aceita NULL", true);
        JTextField fallback = new JTextField(16);
        Form form = new Form();
        form.add("Nome", name);
        form.add("Tipo", type);
        form.add("Tamanho", size);
        form.add("Escala", scale);
        form.span(nullable);
        form.add("Default", fallback);
        return open(parent, "Nova coluna em " + table, form.panel, () -> {
            try {
                Ddl.ColumnDraft draft = new Ddl.ColumnDraft(name.getText(), String.valueOf(type.getSelectedItem()),
                        Integer.parseInt(size.getText().trim()), Integer.parseInt(scale.getText().trim()),
                        nullable.isSelected(), false, false, false, fallback.getText());
                Commands.run(connection, Ddl.addColumn(schema, table, draft));
                return true;
            } catch (RuntimeException | SQLException exception) {
                error(parent, message(exception));
                return false;
            }
        });
    }

    static boolean grant(Component parent, Connection connection, String schema, String table) {
        JTextField schemaField = new JTextField(schema == null ? "public" : schema, 16);
        JTextField tableField = new JTextField(table == null ? "" : table, 16);
        schemaField.setEnabled(table == null);
        tableField.setEnabled(table == null);
        JTextField grantee = new JTextField(16);
        JCheckBox select = new JCheckBox("SELECT", true);
        JCheckBox insert = new JCheckBox("INSERT");
        JCheckBox update = new JCheckBox("UPDATE");
        JCheckBox delete = new JCheckBox("DELETE");
        JCheckBox option = new JCheckBox("WITH GRANT OPTION");
        Form form = new Form();
        form.add("Esquema", schemaField);
        form.add("Tabela", tableField);
        form.add("Para", grantee);
        JPanel privileges = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        privileges.setOpaque(false);
        privileges.add(select);
        privileges.add(insert);
        privileges.add(update);
        privileges.add(delete);
        form.span(privileges);
        form.span(option);
        return open(parent, "Conceder", form.panel, () -> {
            try {
                List<String> chosen = new ArrayList<>();
                if (select.isSelected()) {
                    chosen.add("SELECT");
                }
                if (insert.isSelected()) {
                    chosen.add("INSERT");
                }
                if (update.isSelected()) {
                    chosen.add("UPDATE");
                }
                if (delete.isSelected()) {
                    chosen.add("DELETE");
                }
                Commands.run(connection, Ddl.grant(chosen, schemaField.getText().trim(), tableField.getText().trim(),
                        grantee.getText(), option.isSelected()));
                return true;
            } catch (RuntimeException | SQLException exception) {
                error(parent, message(exception));
                return false;
            }
        });
    }

    static boolean user(Component parent, Connection connection) {
        JTextField name = new JTextField(16);
        JPasswordField password = new JPasswordField(16);
        JTextField first = new JTextField(16);
        JTextField last = new JTextField(16);
        JCheckBox active = new JCheckBox("Ativo", true);
        JCheckBox admin = new JCheckBox("Administrador");
        Form form = new Form();
        form.add("Usuário", name);
        form.add("Senha", password);
        form.add("Nome", first);
        form.add("Sobrenome", last);
        form.span(active);
        form.span(admin);
        return open(parent, "Novo usuário", form.panel, () -> {
            try {
                if (name.getText().isBlank() || password.getPassword().length == 0) {
                    throw new IllegalArgumentException("informe usuário e senha");
                }
                try (var statement = connection.prepareStatement("""
                        INSERT INTO NEX$USERS (NEX$USER_NAME, NEX$PASSWORD, NEX$FIRST_NAME, NEX$LAST_NAME, NEX$ACTIVE, NEX$ADMIN)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, name.getText().trim());
                    statement.setString(2, new String(password.getPassword()));
                    setText(statement, 3, first.getText());
                    setText(statement, 4, last.getText());
                    statement.setBoolean(5, active.isSelected());
                    statement.setBoolean(6, admin.isSelected());
                    statement.executeUpdate();
                }
                return true;
            } catch (RuntimeException | SQLException exception) {
                error(parent, message(exception));
                return false;
            }
        });
    }

    static boolean changePassword(Component parent, Connection connection, String user) {
        JPasswordField password = new JPasswordField(16);
        Form form = new Form();
        form.add("Nova senha de " + user, password);
        return open(parent, "Senha", form.panel, () -> {
            try {
                if (password.getPassword().length == 0) {
                    throw new IllegalArgumentException("informe a senha");
                }
                try (var statement = connection.prepareStatement(
                        "UPDATE NEX$USERS SET NEX$PASSWORD = ? WHERE NEX$USER_NAME = ?")) {
                    statement.setString(1, new String(password.getPassword()));
                    statement.setString(2, user);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("usuário não encontrado: " + user);
                    }
                }
                return true;
            } catch (RuntimeException | SQLException exception) {
                error(parent, message(exception));
                return false;
            }
        });
    }

    private static void setText(java.sql.PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        } else {
            statement.setString(index, value.trim());
        }
    }

    private static boolean open(Component parent, String title, JPanel form, java.util.function.BooleanSupplier accept) {
        JDialog dialog = dialog(parent, title, form);
        final boolean[] ok = {false};
        JButton confirm = Look.primary("OK");
        JButton cancel = Look.button("Cancelar");
        confirm.addActionListener(event -> {
            if (accept != null && !accept.getAsBoolean()) {
                return;
            }
            ok[0] = true;
            dialog.dispose();
        });
        cancel.addActionListener(event -> dialog.dispose());
        dialog.add(actions(confirm, cancel), BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(confirm);
        show(dialog);
        return ok[0];
    }

    private static JDialog dialog(Component parent, String title, JPanel content) {
        Window owner = parent instanceof Window window ? window : javax.swing.SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, title, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        if (content.getParent() == null) {
            JPanel padded = content;
            if (!(content.getBorder() instanceof EmptyBorder)) {
                padded = new JPanel(new BorderLayout());
                padded.setBorder(new EmptyBorder(12, 12, 12, 12));
                padded.add(content, BorderLayout.CENTER);
            }
            dialog.add(padded, BorderLayout.CENTER);
        } else {
            dialog.add(content, BorderLayout.CENTER);
        }
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        return dialog;
    }

    private static void show(JDialog dialog) {
        dialog.pack();
        dialog.setMinimumSize(dialog.getSize());
        dialog.setLocationRelativeTo(dialog.getOwner());
        dialog.setVisible(true);
    }

    private static JPanel actions(JButton ok, JButton cancel) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        panel.add(cancel);
        panel.add(ok);
        return panel;
    }

    private static JPanel row(JComponent field, JButton button) {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.setOpaque(false);
        panel.add(field, BorderLayout.CENTER);
        panel.add(button, BorderLayout.EAST);
        return panel;
    }

    private static Path chooseFile(Component parent, boolean save) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Banco Nexora (*.db)", "db"));
        int result = save ? chooser.showSaveDialog(parent) : chooser.showOpenDialog(parent);
        if (result != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return null;
        }
        return chooser.getSelectedFile().toPath();
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static int parsePort(String text) {
        try {
            int port = Integer.parseInt(text.trim());
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("porta inválida");
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("porta inválida");
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static int number(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null || value.toString().isBlank()) {
            return 0;
        }
        return Integer.parseInt(value.toString().trim());
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean bool && bool;
    }

    private static final class Form {
        final JPanel panel = new JPanel(new GridBagLayout());
        private int row;

        void add(String label, JComponent field) {
            GridBagConstraints constraints = constraints();
            constraints.gridx = 0;
            constraints.weightx = 0;
            constraints.fill = GridBagConstraints.NONE;
            constraints.anchor = GridBagConstraints.LINE_END;
            JLabel caption = new JLabel(label);
            caption.setFont(Look.UI);
            panel.add(caption, constraints);
            constraints.gridx = 1;
            constraints.weightx = 1;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            constraints.anchor = GridBagConstraints.LINE_START;
            field.setFont(Look.UI);
            panel.add(field, constraints);
            row++;
        }

        void span(JComponent field) {
            GridBagConstraints constraints = constraints();
            constraints.gridx = 1;
            constraints.weightx = 1;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            field.setFont(Look.UI);
            panel.add(field, constraints);
            row++;
        }

        private GridBagConstraints constraints() {
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridy = row;
            constraints.insets = new Insets(4, 4, 4, 4);
            return constraints;
        }
    }
}
