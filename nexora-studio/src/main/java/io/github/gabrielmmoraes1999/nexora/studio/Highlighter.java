package io.github.gabrielmmoraes1999.nexora.studio;

import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.util.Locale;
import java.util.Set;

final class Highlighter {

    private static final Set<String> KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "IN", "IS", "NULL", "LIKE", "BETWEEN", "ESCAPE",
            "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "ALTER", "DROP", "TABLE", "INDEX",
            "UNIQUE", "PRIMARY", "KEY", "COLUMN", "ADD", "DEFAULT", "AUTOINCREMENT", "IDENTITY", "CONSTRAINT",
            "FOREIGN", "REFERENCES", "USING", "ASCENDING", "DESCENDING", "ACTION",
            "VIEW", "PROCEDURE", "RETURNS", "TRIGGER", "FOR", "ACTIVE", "INACTIVE", "BEFORE", "AFTER", "POSITION",
            "GENERATOR", "SEQUENCE", "ROLE", "SCHEMA", "GRANT", "REVOKE", "ALL", "TO", "ON", "PUBLIC", "WITH",
            "OPTION", "IF", "EXISTS", "CASCADE", "RESTRICT", "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION", "WORK",
            "START", "ORDER", "BY", "ASC", "DESC", "LIMIT", "OFFSET", "DISTINCT", "AS", "JOIN", "INNER",
            "LEFT", "RIGHT", "FULL", "CROSS", "OUTER", "GROUP", "HAVING",
            "UNION", "CASE", "WHEN", "THEN", "ELSE", "END", "WHILE", "DO", "SUSPEND", "EXIT", "EXCEPTION",
            "DECLARE", "VARIABLE", "CURSOR", "OPEN", "FETCH", "CLOSE", "EXECUTE", "RETURNING_VALUES", "ROW_COUNT",
            "NEW", "OLD", "TRUE", "FALSE", "SMALLINT", "INTEGER", "INT", "BIGINT", "FLOAT", "REAL", "DOUBLE",
            "PRECISION", "DECIMAL", "NUMERIC", "BOOLEAN", "BOOL", "CHAR", "CHARACTER", "VARCHAR", "VARYING",
            "DATE", "TIME", "TIMESTAMP", "BLOB", "BYTEA", "BINARY", "VARBINARY", "COALESCE", "COUNT", "SUM",
            "AVG", "MIN", "MAX", "LOWER", "UPPER", "LENGTH", "CHAR_LENGTH", "CURRENT_DATE", "CURRENT_TIME",
            "CURRENT_TIMESTAMP", "LOCALTIME", "LOCALTIMESTAMP", "CURRENT_USER", "USER", "CURRENT_ROLE",
            "CURRENT_CONNECTION", "CURRENT_TRANSACTION", "GEN_ID", "NEXT", "VALUE", "OF");

    private static final SimpleAttributeSet PLAIN = style(Look.INK, false);
    private static final SimpleAttributeSet KEYWORD = style(Look.KEYWORD, true);
    private static final SimpleAttributeSet STRING = style(Look.STRING, false);
    private static final SimpleAttributeSet COMMENT = style(Look.COMMENT, false);
    private static final SimpleAttributeSet QUOTED = style(Look.QUOTED, false);

    private Highlighter() {
    }

    static boolean keyword(String word) {
        return word != null && KEYWORDS.contains(word.toUpperCase(Locale.ROOT));
    }

    static void watch(JTextPane pane) {
        pane.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private boolean scheduled;

            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                schedule();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                schedule();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
            }

            private void schedule() {
                if (scheduled) {
                    return;
                }
                scheduled = true;
                javax.swing.SwingUtilities.invokeLater(() -> {
                    scheduled = false;
                    paint(pane);
                });
            }
        });
        paint(pane);
    }

    static void paint(JTextPane pane) {
        StyledDocument document = pane.getStyledDocument();
        String text;
        try {
            text = document.getText(0, document.getLength());
        } catch (BadLocationException exception) {
            return;
        }
        if (text.length() > 100_000) {
            return;
        }
        int index = 0;
        while (index < text.length()) {
            int start = index;
            SimpleAttributeSet style = PLAIN;
            char current = text.charAt(index);
            if (current == '-' && index + 1 < text.length() && text.charAt(index + 1) == '-') {
                index = lineEnd(text, index + 2);
                style = COMMENT;
            } else if (current == '/' && index + 1 < text.length() && text.charAt(index + 1) == '*') {
                index = blockEnd(text, index + 2);
                style = COMMENT;
            } else if (current == '\'') {
                index = stringEnd(text, index + 1, '\'');
                style = STRING;
            } else if (current == '"') {
                index = stringEnd(text, index + 1, '"');
                style = QUOTED;
            } else if (Character.isLetter(current) || current == '_' || current == '$') {
                int end = index + 1;
                while (end < text.length()) {
                    char next = text.charAt(end);
                    if (!Character.isLetterOrDigit(next) && next != '_' && next != '$') {
                        break;
                    }
                    end++;
                }
                String word = text.substring(index, end);
                style = KEYWORDS.contains(word.toUpperCase(Locale.ROOT)) ? KEYWORD : PLAIN;
                index = end;
            } else {
                index++;
            }
            document.setCharacterAttributes(start, index - start, style, true);
        }
    }

    private static int lineEnd(String text, int index) {
        while (index < text.length() && text.charAt(index) != '\n') {
            index++;
        }
        return index;
    }

    private static int blockEnd(String text, int index) {
        while (index + 1 < text.length()) {
            if (text.charAt(index) == '*' && text.charAt(index + 1) == '/') {
                return index + 2;
            }
            index++;
        }
        return text.length();
    }

    private static int stringEnd(String text, int index, char quote) {
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == quote) {
                if (index + 1 < text.length() && text.charAt(index + 1) == quote) {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            if (current == '\n' && quote == '\'') {
                return index;
            }
            index++;
        }
        return text.length();
    }

    private static SimpleAttributeSet style(java.awt.Color color, boolean bold) {
        SimpleAttributeSet set = new SimpleAttributeSet();
        StyleConstants.setFontFamily(set, Look.MONO.getFamily());
        StyleConstants.setFontSize(set, Look.MONO.getSize());
        StyleConstants.setForeground(set, color);
        StyleConstants.setBold(set, bold);
        return set;
    }
}
