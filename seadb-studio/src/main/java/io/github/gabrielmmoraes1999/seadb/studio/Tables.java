package io.github.gabrielmmoraes1999.seadb.studio;

import javax.swing.BorderFactory;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

final class Tables {

    private Tables() {
    }

    static JTable create() {
        JTable table = new JTable();
        install(table);
        return table;
    }

    static void install(JTable table) {
        table.setFont(Look.UI);
        table.setRowHeight(26);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        table.setGridColor(Look.LINE);
        table.setSelectionBackground(Look.SELECTION);
        table.setSelectionForeground(Look.INK);
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        JTableHeader header = table.getTableHeader();
        header.setReorderingAllowed(false);
        header.setDefaultRenderer(new HeaderRenderer());
        header.setBackground(Look.HEADER);
        table.setDefaultRenderer(Object.class, new CellRenderer());
    }

    static int show(JTable table, List<String> headers, List<? extends List<?>> rows) {
        DefaultTableModel model = new DefaultTableModel(headers.toArray(), 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        for (List<?> row : rows) {
            model.addRow(row.toArray());
        }
        table.setModel(model);
        install(table);
        pack(table);
        return rows.size();
    }

    static int showResult(JTable table, ResultSet result) throws SQLException {
        ResultSetMetaData meta = result.getMetaData();
        int count = meta.getColumnCount();
        Vector<String> headers = new Vector<>();
        for (int i = 1; i <= count; i++) {
            headers.add(meta.getColumnLabel(i));
        }
        Vector<Vector<Object>> rows = new Vector<>();
        while (result.next()) {
            Vector<Object> row = new Vector<>();
            for (int i = 1; i <= count; i++) {
                row.add(result.getObject(i));
            }
            rows.add(row);
        }
        DefaultTableModel model = new DefaultTableModel(rows, headers) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table.setModel(model);
        install(table);
        table.setAutoCreateRowSorter(true);
        pack(table);
        return rows.size();
    }

    static void pack(JTable table) {
        for (int column = 0; column < table.getColumnCount(); column++) {
            TableColumn current = table.getColumnModel().getColumn(column);
            int width = 80;
            Component header = table.getTableHeader().getDefaultRenderer()
                    .getTableCellRendererComponent(table, current.getHeaderValue(), false, false, -1, column);
            width = Math.max(width, header.getPreferredSize().width + 16);
            int sample = Math.min(table.getRowCount(), 40);
            for (int row = 0; row < sample; row++) {
                Component cell = table.prepareRenderer(table.getCellRenderer(row, column), row, column);
                width = Math.max(width, cell.getPreferredSize().width + 16);
            }
            current.setPreferredWidth(Math.min(width, 320));
        }
    }

    static void copy(JTable table) {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0 && table.getRowCount() > 0) {
            rows = new int[table.getRowCount()];
            for (int i = 0; i < rows.length; i++) {
                rows[i] = i;
            }
        }
        StringBuilder text = new StringBuilder();
        for (int column = 0; column < table.getColumnCount(); column++) {
            if (column > 0) {
                text.append('\t');
            }
            text.append(table.getColumnName(column));
        }
        text.append('\n');
        for (int row : rows) {
            int model = table.convertRowIndexToModel(row);
            for (int column = 0; column < table.getColumnCount(); column++) {
                if (column > 0) {
                    text.append('\t');
                }
                int modelColumn = table.convertColumnIndexToModel(column);
                Object value = table.getModel().getValueAt(model, modelColumn);
                text.append(value == null ? "NULL" : ValuesText.show(value).replace('\t', ' ').replace('\n', ' '));
            }
            text.append('\n');
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text.toString()), null);
    }

    static List<Object> row(JTable table, int viewRow) {
        int model = table.convertRowIndexToModel(viewRow);
        List<Object> values = new ArrayList<>();
        for (int column = 0; column < table.getModel().getColumnCount(); column++) {
            values.add(table.getModel().getValueAt(model, column));
        }
        return values;
    }

    private static final class HeaderRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                        boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, false, false, row, column);
            setFont(Look.UI_BOLD);
            setBackground(Look.HEADER);
            setForeground(Look.NAVY);
            setHorizontalAlignment(SwingConstants.LEFT);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 1, Look.LINE),
                    BorderFactory.createEmptyBorder(6, 8, 6, 8)));
            return this;
        }
    }

    private static final class CellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                        boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            if (value == null) {
                setText("NULL");
                setForeground(selected ? Look.INK : Look.MUTED);
                setFont(Look.UI.deriveFont(java.awt.Font.ITALIC));
            } else {
                setText(ValuesText.show(value));
                setForeground(Look.INK);
                setFont(Look.UI);
            }
            if (!selected) {
                setBackground(row % 2 == 0 ? java.awt.Color.WHITE : Look.ALT);
            }
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            return this;
        }
    }
}
