package io.github.gabrielmmoraes1999.nexora.studio;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.Consumer;

final class Icons {

    static final Icon FOLDER = paint(g -> {
        g.setColor(new Color(0xE0B44C));
        g.fillRoundRect(1, 4, 14, 10, 2, 2);
        g.fillRoundRect(1, 3, 7, 3, 2, 2);
    });

    private Icons() {
    }

    static Icon of(Node node, boolean connected) {
        return switch (node.kind()) {
            case DATABASE -> database(connected);
            case FOLDER -> folder(node.name());
            case TABLE -> table(new Color(0x1F6AA5));
            case SYSTEM_TABLE -> table(new Color(0x667085));
            case VIEW -> layers(new Color(0x0E7C86));
            case PROCEDURE -> scroll(new Color(0x6941C6));
            case TRIGGER -> bolt();
            case GENERATOR -> gauge();
            case SCHEMA -> layers(new Color(0x1F6AA5));
            case ROLE -> key();
            case USER -> person();
            case CONNECTIONS -> link();
            case PRIVILEGES -> key();
            case CONFIG -> sliders();
            case INFO -> info();
        };
    }

    private static Icon folder(String name) {
        return switch (name) {
            case Node.USERS -> person();
            case Node.ROLES -> key();
            case Node.SYSTEM -> table(new Color(0x667085));
            default -> FOLDER;
        };
    }

    private static Icon database(boolean connected) {
        Color color = connected ? new Color(0x1F6AA5) : new Color(0x98A2B3);
        return paint(g -> {
            g.setColor(color);
            g.fillRoundRect(2, 4, 12, 9, 3, 3);
            g.setColor(Color.WHITE);
            g.fillOval(4, 2, 8, 4);
            g.setColor(color);
            g.drawOval(4, 2, 8, 4);
        });
    }

    private static Icon table(Color color) {
        return paint(g -> {
            g.setColor(color);
            g.drawRect(2, 3, 12, 10);
            g.drawLine(2, 6, 14, 6);
            g.drawLine(2, 9, 14, 9);
            g.drawLine(7, 6, 7, 13);
        });
    }

    private static Icon layers(Color color) {
        return paint(g -> {
            g.setColor(color);
            g.drawRoundRect(3, 3, 10, 7, 2, 2);
            g.drawRoundRect(2, 6, 10, 7, 2, 2);
        });
    }

    private static Icon scroll(Color color) {
        return paint(g -> {
            g.setColor(color);
            g.drawRoundRect(4, 2, 8, 12, 3, 3);
            g.drawLine(6, 6, 10, 6);
            g.drawLine(6, 8, 10, 8);
            g.drawLine(6, 10, 9, 10);
        });
    }

    private static Icon bolt() {
        return paint(g -> {
            g.setColor(new Color(0xDC6803));
            int[] xs = {9, 4, 8, 7, 12, 7, 8};
            int[] ys = {1, 8, 8, 15, 6, 6, 1};
            g.fillPolygon(xs, ys, xs.length);
        });
    }

    private static Icon gauge() {
        return paint(g -> {
            g.setColor(new Color(0x067647));
            g.drawOval(2, 2, 12, 12);
            g.drawLine(8, 8, 11, 5);
        });
    }

    private static Icon key() {
        return paint(g -> {
            g.setColor(new Color(0xB54708));
            g.drawOval(2, 4, 6, 6);
            g.drawLine(8, 7, 14, 7);
            g.drawLine(12, 7, 12, 9);
            g.drawLine(14, 7, 14, 9);
        });
    }

    private static Icon person() {
        return paint(g -> {
            g.setColor(new Color(0x344054));
            g.drawOval(5, 2, 6, 6);
            g.drawArc(3, 9, 10, 8, 0, 180);
        });
    }

    private static Icon link() {
        return paint(g -> {
            g.setColor(new Color(0x1F6AA5));
            g.fillOval(2, 6, 4, 4);
            g.fillOval(10, 6, 4, 4);
            g.drawLine(6, 8, 10, 8);
        });
    }

    private static Icon sliders() {
        return paint(g -> {
            g.setColor(new Color(0x344054));
            g.drawLine(3, 4, 13, 4);
            g.drawLine(3, 8, 13, 8);
            g.drawLine(3, 12, 13, 12);
            g.fillRect(5, 3, 2, 3);
            g.fillRect(9, 7, 2, 3);
            g.fillRect(6, 11, 2, 3);
        });
    }

    private static Icon info() {
        return paint(g -> {
            g.setColor(new Color(0x1F6AA5));
            g.drawOval(2, 2, 12, 12);
            g.drawLine(8, 7, 8, 12);
            g.fillOval(7, 4, 2, 2);
        });
    }

    private static Icon paint(Consumer<Graphics2D> painter) {
        return new Icon() {
            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }

            @Override
            public void paintIcon(Component component, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setStroke(new BasicStroke(1.2f));
                g.translate(x, y);
                painter.accept(g);
                g.dispose();
            }
        };
    }
}
