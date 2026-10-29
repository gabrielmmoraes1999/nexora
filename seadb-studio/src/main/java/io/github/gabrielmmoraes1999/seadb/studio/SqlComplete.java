package io.github.gabrielmmoraes1999.seadb.studio;

import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.JWindow;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Popup of {@link Completion} suggestions on the SQL editor.
 * Ctrl+Space opens it. Typing a name, a dot, or a space after a clause opens it too.
 */
final class SqlComplete {

    private final JTextPane editor;
    private final Hub hub;
    private final String databaseId;
    private final DefaultListModel<Completion.Item> model = new DefaultListModel<>();
    private final JList<Completion.Item> list = new JList<>(model);
    private final JScrollPane scroll = new JScrollPane(list);
    private final Map<String, List<String>> columns = new HashMap<>();

    private JWindow window;
    private Outline outline;
    private long loadedAt;
    private int replaceStart;
    private boolean applying;
    private boolean posted;
    private boolean manual;

    private SqlComplete(JTextPane editor, Hub hub, String databaseId) {
        this.editor = editor;
        this.hub = hub;
        this.databaseId = databaseId;
    }

    static void attach(JTextPane editor, Hub hub, String databaseId) {
        new SqlComplete(editor, hub, databaseId).install();
    }

    private void install() {
        list.setCellRenderer(new Cell());
        list.setFont(Look.MONO);
        list.setBackground(Color.WHITE);
        list.setSelectionBackground(Look.SELECTION);
        list.setSelectionForeground(Look.INK);
        list.setFixedCellHeight(24);
        list.setFocusable(false);
        list.setRequestFocusEnabled(false);
        scroll.setFocusable(false);
        scroll.setBorder(javax.swing.BorderFactory.createLineBorder(Look.LINE));
        scroll.getViewport().setBackground(Color.WHITE);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int index = list.locationToIndex(event.getPoint());
                if (index >= 0) {
                    list.setSelectedIndex(index);
                    accept();
                }
            }
        });
        editor.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_SPACE && event.isControlDown() && !event.isAltDown()) {
                    event.consume();
                    schedule(true);
                    return;
                }
                if (!visible()) {
                    return;
                }
                switch (event.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> move(1, event);
                    case KeyEvent.VK_UP -> move(-1, event);
                    case KeyEvent.VK_PAGE_DOWN -> move(8, event);
                    case KeyEvent.VK_PAGE_UP -> move(-8, event);
                    case KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                        event.consume();
                        accept();
                    }
                    case KeyEvent.VK_ESCAPE -> {
                        event.consume();
                        hide();
                    }
                    default -> {
                    }
                }
            }
        });
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                if (!applying && event.getLength() == 1) {
                    schedule(false);
                }
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                if (!applying && visible()) {
                    schedule(true);
                }
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
            }
        });
        editor.addCaretListener(event -> {
            if (!applying && visible()) {
                schedule(true);
            }
        });
        editor.addHierarchyListener(event -> {
            if (!editor.isShowing()) {
                hide();
            }
        });
    }

    private void schedule(boolean force) {
        manual = manual || force;
        if (posted) {
            return;
        }
        posted = true;
        SwingUtilities.invokeLater(() -> {
            posted = false;
            boolean forced = manual;
            manual = false;
            show(forced);
        });
    }

    private void show(boolean forced) {
        if (applying || !editor.isDisplayable()) {
            return;
        }
        int caret = editor.getCaretPosition();
        String text = editor.getText();
        if (!forced && !worthOpening(text, caret)) {
            return;
        }
        Completion.Catalog catalog = catalog();
        Completion.Result result = Completion.suggest(text, caret, catalog);
        if (!result.missing().isEmpty()) {
            loadColumns(result.missing());
            result = Completion.suggest(text, caret, catalog());
        }
        if (result.items().isEmpty()) {
            hide();
            return;
        }
        String selected = list.getSelectedValue() == null ? null : list.getSelectedValue().insert();
        model.clear();
        int index = 0;
        for (int i = 0; i < result.items().size(); i++) {
            Completion.Item item = result.items().get(i);
            model.addElement(item);
            if (item.insert().equals(selected)) {
                index = i;
            }
        }
        list.setSelectedIndex(index);
        list.ensureIndexIsVisible(index);
        replaceStart = result.replaceStart();
        place(Math.min(12, model.getSize()));
    }

    private boolean worthOpening(String text, int caret) {
        if (caret <= 0 || caret > text.length()) {
            return false;
        }
        char current = text.charAt(caret - 1);
        return Character.isLetterOrDigit(current) || current == '_' || current == '$' || current == '.'
                || current == ' ' || current == '\n' || current == '(';
    }

    private void place(int rows) {
        try {
            var caret = editor.modelToView2D(editor.getCaretPosition());
            if (caret == null) {
                hide();
                return;
            }
            list.setVisibleRowCount(rows);
            JWindow popup = window();
            popup.pack();
            int width = Math.max(280, popup.getPreferredSize().width);
            int height = rows * list.getFixedCellHeight() + 2;
            popup.setSize(width, height);
            Point point = new Point((int) caret.getX(), (int) (caret.getY() + caret.getHeight()));
            SwingUtilities.convertPointToScreen(point, editor);
            popup.setLocation(point);
            if (!popup.isVisible()) {
                popup.setVisible(true);
            }
        } catch (BadLocationException exception) {
            hide();
        }
    }

    private JWindow window() {
        if (window == null) {
            Window owner = SwingUtilities.getWindowAncestor(editor);
            window = new JWindow(owner);
            window.setFocusableWindowState(false);
            window.getContentPane().setBackground(Color.WHITE);
            window.getContentPane().add(scroll);
        }
        return window;
    }

    private void move(int delta, KeyEvent event) {
        event.consume();
        if (model.isEmpty()) {
            return;
        }
        int next = list.getSelectedIndex() + delta;
        if (next < 0) {
            next = model.getSize() - 1;
        } else if (next >= model.getSize()) {
            next = 0;
        }
        list.setSelectedIndex(next);
        list.ensureIndexIsVisible(next);
    }

    private void accept() {
        Completion.Item item = list.getSelectedValue();
        if (item == null) {
            hide();
            return;
        }
        applying = true;
        try {
            int caret = editor.getCaretPosition();
            int start = Math.max(0, Math.min(replaceStart, caret));
            editor.getDocument().remove(start, caret - start);
            editor.getDocument().insertString(start, item.insert(), null);
            editor.setCaretPosition(start + item.insert().length());
        } catch (BadLocationException exception) {
            hide();
            return;
        } finally {
            applying = false;
        }
        hide();
        if (item.openAgain()) {
            schedule(true);
        }
    }

    private void hide() {
        if (window != null) {
            window.setVisible(false);
        }
    }

    private boolean visible() {
        return window != null && window.isVisible();
    }

    private Completion.Catalog catalog() {
        String schema = hub.currentSchema(databaseId);
        Connection connection = hub.connection(databaseId);
        if (connection == null) {
            outline = null;
            return Completion.Catalog.empty(schema, columns);
        }
        long now = System.nanoTime();
        if (outline == null || now - loadedAt > 2_000_000_000L) {
            try {
                outline = Outline.load(connection);
                loadedAt = now;
                columns.clear();
            } catch (SQLException exception) {
                if (outline == null) {
                    return Completion.Catalog.empty(schema, columns);
                }
            }
        }
        return Completion.Catalog.capture(outline, schema, columns);
    }

    private void loadColumns(List<Completion.ObjectName> missing) {
        Connection connection = hub.connection(databaseId);
        if (connection == null) {
            return;
        }
        for (Completion.ObjectName name : missing) {
            String schema = name.schema() == null || name.schema().isBlank()
                    ? hub.currentSchema(databaseId) : name.schema();
            String mapKey = Completion.key(schema, name.name());
            if (columns.containsKey(mapKey)) {
                continue;
            }
            try {
                List<String> names = new ArrayList<>();
                for (Ddl.ColumnInfo column : Outline.columns(connection, schema, name.name())) {
                    names.add(column.name());
                }
                columns.put(mapKey, List.copyOf(names));
            } catch (SQLException exception) {
                columns.put(mapKey, List.of());
            }
        }
    }

    private static final class Cell extends javax.swing.JPanel implements ListCellRenderer<Completion.Item> {
        private final javax.swing.JLabel name = new javax.swing.JLabel();
        private final javax.swing.JLabel detail = new javax.swing.JLabel();

        private Cell() {
            super(new BorderLayout(16, 0));
            setBorder(new EmptyBorder(0, 8, 0, 8));
            name.setFont(Look.MONO);
            detail.setFont(Look.UI);
            add(name, BorderLayout.CENTER);
            add(detail, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Completion.Item> list, Completion.Item item,
                                                      int index, boolean selected, boolean focus) {
            name.setText(item == null ? "" : item.label());
            detail.setText(item == null ? "" : item.detail());
            setBackground(selected ? Look.SELECTION : Color.WHITE);
            name.setForeground(Look.INK);
            detail.setForeground(Look.MUTED);
            return this;
        }
    }
}
