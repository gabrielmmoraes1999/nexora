package io.github.gabrielmmoraes1999.nexora.studio;

import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Grade de uma tabela. A edição usa a chave primária no {@code WHERE}.
 */
final class DataPane extends JPanel {

    private final Hub hub;
    private final String databaseId;
    private final String schema;
    private final String table;
    private final boolean wantEdit;
    private final JTextField filter = new JTextField(24);
    private final JSpinner limit = new JSpinner(new SpinnerNumberModel(200, 1, 10000, 50));
    private final JLabel banner = new JLabel(" ");
    private final JTable grid = Tables.create();
    private final Model model = new Model();
    private final List<Column> columns = new ArrayList<>();
    private final List<Object[]> rows = new ArrayList<>();
    private final List<Object[]> originals = new ArrayList<>();
    private boolean editable;
    private boolean loaded;

    DataPane(Hub hub, String databaseId, String schema, String table, boolean wantEdit) {
        super(new BorderLayout());
        this.hub = hub;
        this.databaseId = databaseId;
        this.schema = schema;
        this.table = table;
        this.wantEdit = wantEdit;
        banner.setFont(Look.UI);
        banner.setForeground(Look.MUTED);
        banner.setBorder(new EmptyBorder(4, 8, 4, 8));
        grid.setModel(model);
        Tables.install(grid);
        installEditor();
        grid.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        add(toolbar(), BorderLayout.NORTH);
        add(banner, BorderLayout.SOUTH);
        add(Look.scroll(grid), BorderLayout.CENTER);
    }

    boolean loaded() {
        return loaded;
    }

    void load() {
        Connection connection = hub.connection(databaseId);
        if (connection == null) {
            banner.setText("Banco desconectado.");
            return;
        }
        String where = filter.getText().trim();
        if (where.indexOf(';') >= 0) {
            Dialogs.error(hub.owner(), "o filtro não pode ter ponto e vírgula");
            return;
        }
        hub.owner().setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        try {
            List<Ddl.ColumnInfo> described = Outline.columns(connection, schema, table);
            StringBuilder sql = new StringBuilder("SELECT * FROM ").append(SqlNames.qualify(schema, table));
            if (!where.isEmpty()) {
                sql.append(" WHERE ").append(where);
            }
            List<String> keys = new ArrayList<>();
            for (Ddl.ColumnInfo column : described) {
                if (column.primaryKeyPosition() > 0) {
                    while (keys.size() < column.primaryKeyPosition()) {
                        keys.add("");
                    }
                    keys.set(column.primaryKeyPosition() - 1, column.name());
                }
            }
            keys.removeIf(String::isBlank);
            if (!keys.isEmpty()) {
                sql.append(" ORDER BY ");
                for (int i = 0; i < keys.size(); i++) {
                    if (i > 0) {
                        sql.append(", ");
                    }
                    sql.append(SqlNames.quote(keys.get(i)));
                }
            }
            sql.append(" LIMIT ").append(((Number) limit.getValue()).intValue());
            columns.clear();
            rows.clear();
            originals.clear();
            try (java.sql.Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql.toString())) {
                ResultSetMetaData meta = result.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    String name = meta.getColumnLabel(i);
                    Ddl.ColumnInfo info = find(described, name);
                    columns.add(new Column(
                            name,
                            info == null ? meta.getColumnTypeName(i) : info.typeName(),
                            meta.getColumnType(i),
                            info == null ? meta.isNullable(i) != ResultSetMetaData.columnNoNulls : info.nullable(),
                            info != null && info.autoIncrement(),
                            info == null ? 0 : info.primaryKeyPosition(),
                            "BLOB".equalsIgnoreCase(meta.getColumnTypeName(i))));
                }
                while (result.next()) {
                    Object[] values = new Object[columns.size()];
                    for (int i = 0; i < columns.size(); i++) {
                        values[i] = result.getObject(i + 1);
                    }
                    rows.add(values);
                    originals.add(values.clone());
                }
            }
            editable = wantEdit && !system() && !keys.isEmpty();
            if (!wantEdit) {
                banner.setText("Somente leitura.");
            } else if (system()) {
                banner.setText("Tabela de sistema: somente leitura. Use o SQL para o que o DBA pode gravar.");
            } else if (keys.isEmpty()) {
                banner.setText("Sem chave primária: a grade é somente leitura.");
            } else {
                banner.setText(rows.size() + " linha(s). Célula vazia grava NULL. Em CHAR e VARCHAR, '' grava texto vazio.");
            }
            loaded = true;
            model.fireTableStructureChanged();
            Tables.install(grid);
            installEditor();
            Tables.pack(grid);
        } catch (SQLException | RuntimeException exception) {
            banner.setText(Dialogs.message(exception));
            Dialogs.error(hub.owner(), Dialogs.message(exception));
        } finally {
            hub.owner().setCursor(java.awt.Cursor.getDefaultCursor());
        }
    }

    private void installEditor() {
        JTextField field = new JTextField();
        grid.setDefaultEditor(Object.class, new DefaultCellEditor(field) {
            @Override
            public java.awt.Component getTableCellEditorComponent(JTable table, Object value, boolean selected, int row, int column) {
                super.getTableCellEditorComponent(table, value, selected, row, column);
                field.setText(ValuesText.edit(value));
                return field;
            }
        });
    }

    private boolean system() {
        return table.regionMatches(true, 0, "NEX$", 0, 4);
    }

    private JPanel toolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.setBackground(new java.awt.Color(0xF7F8FA));
        JButton refresh = Look.button("Atualizar");
        JButton insert = Look.button("Inserir");
        JButton delete = Look.button("Excluir");
        JButton copy = Look.button("Copiar");
        refresh.addActionListener(event -> load());
        insert.addActionListener(event -> insert());
        delete.addActionListener(event -> delete());
        copy.addActionListener(event -> Tables.copy(grid));
        filter.setToolTipText("Expressão do WHERE, sem ponto e vírgula");
        bar.add(refresh);
        bar.add(insert);
        bar.add(delete);
        bar.add(copy);
        bar.add(new JLabel("WHERE"));
        bar.add(filter);
        bar.add(new JLabel("limite"));
        bar.add(limit);
        return bar;
    }

    private void insert() {
        if (!editable) {
            Dialogs.error(hub.owner(), banner.getText());
            return;
        }
        Connection connection = hub.connection(databaseId);
        if (connection == null) {
            return;
        }
        Window owner = hub.owner() instanceof Window window ? window : javax.swing.SwingUtilities.getWindowAncestor(hub.owner());
        javax.swing.JDialog dialog = new javax.swing.JDialog(owner, "Inserir em " + table, Dialog.ModalityType.APPLICATION_MODAL);
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(12, 12, 12, 12));
        List<JTextField> fields = new ArrayList<>();
        List<JCheckBox> nulls = new ArrayList<>();
        List<JCheckBox> autos = new ArrayList<>();
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(3, 4, 3, 4);
        constraints.anchor = GridBagConstraints.LINE_START;
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            constraints.gridx = 0;
            constraints.gridy = i;
            constraints.weightx = 0;
            form.add(new JLabel(column.name + "  " + column.type), constraints);
            JTextField field = new JTextField(18);
            JCheckBox nil = new JCheckBox("NULL");
            JCheckBox auto = new JCheckBox("Automático", column.auto);
            fields.add(field);
            nulls.add(nil);
            autos.add(auto);
            if (column.auto) {
                field.setEnabled(false);
                nil.setEnabled(false);
            }
            auto.addActionListener(event -> {
                boolean generated = auto.isSelected();
                field.setEnabled(!generated && !nil.isSelected());
                nil.setEnabled(!generated);
            });
            nil.addActionListener(event -> field.setEnabled(!nil.isSelected() && !auto.isSelected()));
            constraints.gridx = 1;
            constraints.weightx = 1;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            form.add(field, constraints);
            constraints.gridx = 2;
            constraints.weightx = 0;
            constraints.fill = GridBagConstraints.NONE;
            form.add(nil, constraints);
            constraints.gridx = 3;
            form.add(auto, constraints);
        }
        JButton ok = Look.primary("Inserir");
        JButton cancel = Look.button("Cancelar");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(cancel);
        actions.add(ok);
        dialog.add(new JScrollPane(form), BorderLayout.CENTER);
        dialog.add(actions, BorderLayout.SOUTH);
        cancel.addActionListener(event -> dialog.dispose());
        ok.addActionListener(event -> {
            try {
                List<String> names = new ArrayList<>();
                List<Object> values = new ArrayList<>();
                List<Column> used = new ArrayList<>();
                for (int i = 0; i < columns.size(); i++) {
                    if (autos.get(i).isSelected()) {
                        continue;
                    }
                    names.add(columns.get(i).name);
                    used.add(columns.get(i));
                    if (nulls.get(i).isSelected()) {
                        values.add(null);
                    } else {
                        values.add(ValuesText.parse(fields.get(i).getText(), columns.get(i).type, columns.get(i).nullable));
                    }
                }
                if (names.isEmpty()) {
                    throw new IllegalArgumentException("informe ao menos um valor, ou desmarque Automático");
                }
                StringBuilder sql = new StringBuilder("INSERT INTO ").append(SqlNames.qualify(schema, table)).append(" (");
                for (int i = 0; i < names.size(); i++) {
                    if (i > 0) {
                        sql.append(", ");
                    }
                    sql.append(SqlNames.quote(names.get(i)));
                }
                sql.append(") VALUES (");
                sql.append("?, ".repeat(names.size()));
                sql.setLength(sql.length() - 2);
                sql.append(')');
                try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                    for (int i = 0; i < values.size(); i++) {
                        bind(statement, i + 1, values.get(i), used.get(i));
                    }
                    statement.executeUpdate();
                }
                dialog.dispose();
                load();
            } catch (RuntimeException | SQLException exception) {
                Dialogs.error(dialog, Dialogs.message(exception));
            }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private void delete() {
        if (!editable) {
            Dialogs.error(hub.owner(), banner.getText());
            return;
        }
        int[] selected = grid.getSelectedRows();
        if (selected.length == 0) {
            return;
        }
        if (!Dialogs.confirm(hub.owner(), "Excluir " + selected.length + " linha(s) de " + table + "?")) {
            return;
        }
        Connection connection = hub.connection(databaseId);
        if (connection == null) {
            return;
        }
        try {
            for (int i = selected.length - 1; i >= 0; i--) {
                int row = grid.convertRowIndexToModel(selected[i]);
                deleteRow(connection, row);
                rows.remove(row);
                originals.remove(row);
            }
            model.fireTableDataChanged();
            banner.setText(rows.size() + " linha(s) depois da exclusão.");
        } catch (SQLException exception) {
            Dialogs.error(hub.owner(), Dialogs.message(exception));
            load();
        }
    }

    private void deleteRow(Connection connection, int row) throws SQLException {
        List<Integer> keys = keyIndexes();
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(SqlNames.qualify(schema, table)).append(" WHERE ");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            sql.append(SqlNames.quote(columns.get(keys.get(i)).name)).append(" = ?");
        }
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < keys.size(); i++) {
                int index = keys.get(i);
                bind(statement, i + 1, originals.get(row)[index], columns.get(index));
            }
            int count = statement.executeUpdate();
            if (count != 1) {
                throw new SQLException("exclusão afetou " + count + " linhas");
            }
        }
    }

    private void update(int row, int column, Object value) throws SQLException {
        Connection connection = hub.connection(databaseId);
        if (connection == null) {
            throw new SQLException("banco desconectado");
        }
        List<Integer> keys = keyIndexes();
        StringBuilder sql = new StringBuilder("UPDATE ").append(SqlNames.qualify(schema, table))
                .append(" SET ").append(SqlNames.quote(columns.get(column).name)).append(" = ? WHERE ");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            sql.append(SqlNames.quote(columns.get(keys.get(i)).name)).append(" = ?");
        }
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, 1, value, columns.get(column));
            for (int i = 0; i < keys.size(); i++) {
                int index = keys.get(i);
                bind(statement, i + 2, originals.get(row)[index], columns.get(index));
            }
            int count = statement.executeUpdate();
            if (count != 1) {
                throw new SQLException("atualização afetou " + count + " linhas");
            }
        }
    }

    private static void bind(PreparedStatement statement, int index, Object value, Column column) throws SQLException {
        if (value == null) {
            statement.setNull(index, column.sqlType == 0 ? Types.VARCHAR : column.sqlType);
        } else {
            statement.setObject(index, value);
        }
    }

    private List<Integer> keyIndexes() {
        List<Integer> keys = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).pk > 0) {
                keys.add(i);
            }
        }
        keys.sort(java.util.Comparator.comparingInt(index -> columns.get(index).pk));
        return keys;
    }

    private static Ddl.ColumnInfo find(List<Ddl.ColumnInfo> columns, String name) {
        for (Ddl.ColumnInfo column : columns) {
            if (SqlNames.same(column.name(), name)) {
                return column;
            }
        }
        return null;
    }

    private record Column(String name, String type, int sqlType, boolean nullable, boolean auto, int pk, boolean blob) {
    }

    private final class Model extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.size();
        }

        @Override
        public String getColumnName(int column) {
            return columns.get(column).name;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return editable && !columns.get(column).blob;
        }

        @Override
        public Object getValueAt(int row, int column) {
            return rows.get(row)[column];
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            Column described = columns.get(column);
            try {
                Object parsed = ValuesText.parse(value == null ? "" : value.toString(), described.type, described.nullable);
                Object current = rows.get(row)[column];
                if (java.util.Objects.equals(current, parsed) || sameText(current, parsed)) {
                    return;
                }
                update(row, column, parsed);
                rows.get(row)[column] = parsed;
                originals.get(row)[column] = parsed;
                fireTableCellUpdated(row, column);
            } catch (RuntimeException | SQLException exception) {
                Dialogs.error(hub.owner(), Dialogs.message(exception));
            }
        }
    }

    private static boolean sameText(Object current, Object parsed) {
        if (current == null || parsed == null) {
            return current == parsed;
        }
        return ValuesText.edit(current).equals(ValuesText.edit(parsed));
    }
}
