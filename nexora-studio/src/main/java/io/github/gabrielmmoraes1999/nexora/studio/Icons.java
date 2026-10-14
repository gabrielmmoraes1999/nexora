package io.github.gabrielmmoraes1999.nexora.studio;

import javax.swing.Icon;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Consumer;

final class Icons {

    private static final Color INK = new Color(0x344054);
    private static final Color ACCENT = new Color(0x1F6AA5);
    private static final Color GREEN = new Color(0x067647);
    private static final Color AMBER = new Color(0xB54708);
    private static final Color VIOLET = new Color(0x6941C6);
    private static final Color TEAL = new Color(0x0E7C86);
    private static final Color GRAY = new Color(0x667085);

    static final Icon FOLDER = paint(g -> {
        g.setColor(new Color(0xE0B44C));
        g.fill(new RoundRectangle2D.Float(1, 4, 14, 10, 2, 2));
        g.fill(new RoundRectangle2D.Float(1, 2.5f, 7, 3.5f, 2, 2));
    });

    static final Icon SQL = paint(g -> document(g, ACCENT));

    static final Icon SEARCH = paint(g -> {
        g.setColor(GRAY);
        g.draw(new Ellipse2D.Float(2, 2, 8, 8));
        g.draw(new Line2D.Float(9, 9, 13.5f, 13.5f));
    });

    static final Icon PLAY = new Icon() {
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
            g.translate(x, y);
            g.setColor(component != null && !component.isEnabled() ? new Color(0x98A2B3) : Color.WHITE);
            g.fillPolygon(new int[]{5, 5, 12}, new int[]{3, 13, 8}, 3);
            g.dispose();
        }
    };

    private Icons() {
    }

    static Icon of(Node node, boolean connected) {
        return switch (node.kind()) {
            case DATABASE -> database(connected);
            case FOLDER -> folder(node.name());
            case TABLE -> table(ACCENT);
            case SYSTEM_TABLE -> table(GRAY);
            case VIEW -> layers(TEAL);
            case PROCEDURE -> scroll(VIOLET);
            case TRIGGER -> bolt();
            case GENERATOR -> gauge();
            case SCHEMA -> layers(ACCENT);
            case ROLE -> key();
            case USER -> person();
            case CONNECTIONS -> link(false);
            case PRIVILEGES -> key();
            case CONFIG -> sliders();
            case INFO -> info();
        };
    }

    static Icon action(String label) {
        return switch (label == null ? "" : label) {
            case "Registrar", "Registrar banco" -> database(true);
            case "Criar banco", "Nova tabela", "Novo esquema", "Novo papel", "Novo", "Nova coluna", "Coluna", "Inserir" ->
                    plus(INK);
            case "Conectar", "Usar nesta conexão" -> link(false);
            case "Desconectar" -> link(true);
            case "Atualizar" -> refresh();
            case "Nova consulta", "DDL" -> SQL;
            case "Executar" -> play(GREEN);
            case "Commit" -> check(GREEN);
            case "Rollback" -> undo();
            case "Sair", "Encerrar" -> exit();
            case "Abrir", "Abrir no editor" -> open();
            case "Dados" -> table(ACCENT);
            case "Excluir", "Remover", "Remover do registro", "Revogar" -> trash();
            case "Copiar", "Copiar grade" -> copy();
            case "Propriedades do registro", "Sobre" -> info();
            case "Usuários" -> person();
            default -> null;
        };
    }

    static Icon plus(Color color) {
        return paint(g -> {
            g.setColor(color);
            g.fill(new RoundRectangle2D.Float(7, 3, 2, 10, 1, 1));
            g.fill(new RoundRectangle2D.Float(3, 7, 10, 2, 1, 1));
        });
    }

    static Icon play(Color color) {
        return paint(g -> {
            g.setColor(color);
            g.fillPolygon(new int[]{5, 5, 12}, new int[]{3, 13, 8}, 3);
        });
    }

    private static Icon folder(String name) {
        return switch (name) {
            case Node.USERS -> person();
            case Node.ROLES -> key();
            case Node.SYSTEM -> table(GRAY);
            default -> FOLDER;
        };
    }

    private static Icon database(boolean connected) {
        Color color = connected ? ACCENT : new Color(0x98A2B3);
        return paint(g -> {
            float left = 2.5f;
            float width = 11f;
            float top = 3f;
            float ellipse = 4f;
            float body = 8f;
            g.setColor(color);
            g.fill(new Rectangle2D.Float(left, top + ellipse / 2f, width, body));
            g.fill(new Ellipse2D.Float(left, top + body - ellipse / 2f, width, ellipse));
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Float(left, top, width, ellipse));
            g.setColor(color);
            g.draw(new Ellipse2D.Float(left, top, width, ellipse));
        });
    }

    private static Icon table(Color color) {
        return paint(g -> {
            g.setColor(color);
            g.draw(new RoundRectangle2D.Float(2, 3, 12, 10, 2, 2));
            g.draw(new Line2D.Float(2, 6.5f, 14, 6.5f));
            g.draw(new Line2D.Float(2, 9.5f, 14, 9.5f));
            g.draw(new Line2D.Float(7, 6.5f, 7, 13));
        });
    }

    private static Icon layers(Color color) {
        return paint(g -> {
            g.setColor(color);
            g.draw(new RoundRectangle2D.Float(3, 3, 10, 7, 2, 2));
            g.draw(new RoundRectangle2D.Float(2, 6, 10, 7, 2, 2));
        });
    }

    private static Icon scroll(Color color) {
        return paint(g -> {
            g.setColor(color);
            g.draw(new RoundRectangle2D.Float(4, 2, 8, 12, 3, 3));
            g.draw(new Line2D.Float(6, 6, 10, 6));
            g.draw(new Line2D.Float(6, 8, 10, 8));
            g.draw(new Line2D.Float(6, 10, 9, 10));
        });
    }

    private static Icon bolt() {
        return paint(g -> {
            g.setColor(new Color(0xDC6803));
            g.fillPolygon(new int[]{9, 4, 8, 7, 12, 7, 8}, new int[]{1, 8, 8, 15, 6, 6, 1}, 7);
        });
    }

    private static Icon gauge() {
        return paint(g -> {
            g.setColor(GREEN);
            g.draw(new Ellipse2D.Float(2, 2, 12, 12));
            g.draw(new Line2D.Float(8, 8, 11, 5));
        });
    }

    private static Icon key() {
        return paint(g -> {
            g.setColor(AMBER);
            g.draw(new Ellipse2D.Float(2, 4, 6, 6));
            g.draw(new Line2D.Float(8, 7, 14, 7));
            g.draw(new Line2D.Float(12, 7, 12, 9));
            g.draw(new Line2D.Float(14, 7, 14, 9));
        });
    }

    private static Icon person() {
        return paint(g -> {
            g.setColor(INK);
            g.draw(new Ellipse2D.Float(5, 2, 6, 6));
            g.draw(new Arc2D.Float(3, 9, 10, 8, 0, 180, Arc2D.OPEN));
        });
    }

    private static Icon link(boolean broken) {
        return paint(g -> {
            g.setColor(broken ? GRAY : ACCENT);
            g.fill(new Ellipse2D.Float(2, 6, 4, 4));
            g.fill(new Ellipse2D.Float(10, 6, 4, 4));
            if (broken) {
                g.draw(new Line2D.Float(6.5f, 6.5f, 9.5f, 9.5f));
            } else {
                g.draw(new Line2D.Float(6, 8, 10, 8));
            }
        });
    }

    private static Icon sliders() {
        return paint(g -> {
            g.setColor(INK);
            g.draw(new Line2D.Float(3, 4, 13, 4));
            g.draw(new Line2D.Float(3, 8, 13, 8));
            g.draw(new Line2D.Float(3, 12, 13, 12));
            g.fill(new Rectangle2D.Float(5, 3, 2, 3));
            g.fill(new Rectangle2D.Float(9, 7, 2, 3));
            g.fill(new Rectangle2D.Float(6, 11, 2, 3));
        });
    }

    private static Icon info() {
        return paint(g -> {
            g.setColor(ACCENT);
            g.draw(new Ellipse2D.Float(2, 2, 12, 12));
            g.draw(new Line2D.Float(8, 7, 8, 12));
            g.fill(new Ellipse2D.Float(7, 4, 2, 2));
        });
    }

    private static Icon refresh() {
        return paint(g -> {
            g.setColor(ACCENT);
            g.draw(new Arc2D.Float(3, 3, 10, 10, 50, 260, Arc2D.OPEN));
            g.fillPolygon(new int[]{10, 14, 10}, new int[]{2, 5, 6}, 3);
        });
    }

    private static Icon check(Color color) {
        return paint(g -> {
            g.setColor(color);
            g.draw(new Line2D.Float(3.5f, 8.5f, 6.5f, 11.5f));
            g.draw(new Line2D.Float(6.5f, 11.5f, 12.5f, 4.5f));
        });
    }

    private static Icon undo() {
        return paint(g -> {
            g.setColor(AMBER);
            g.draw(new Arc2D.Float(4, 3, 9, 9, 20, 230, Arc2D.OPEN));
            g.fillPolygon(new int[]{3, 7, 3}, new int[]{4, 6, 8}, 3);
        });
    }

    private static Icon exit() {
        return paint(g -> {
            g.setColor(INK);
            g.draw(new RoundRectangle2D.Float(2, 3, 7, 10, 2, 2));
            g.draw(new Line2D.Float(8, 8, 14, 8));
            g.draw(new Line2D.Float(11, 5, 14, 8));
            g.draw(new Line2D.Float(11, 11, 14, 8));
        });
    }

    private static Icon open() {
        return paint(g -> {
            g.setColor(ACCENT);
            g.draw(new RoundRectangle2D.Float(2, 3, 8, 10, 2, 2));
            g.draw(new Line2D.Float(8, 6, 13, 3));
            g.draw(new Line2D.Float(10, 3, 13, 3));
            g.draw(new Line2D.Float(13, 3, 13, 6));
        });
    }

    private static Icon trash() {
        return paint(g -> {
            g.setColor(new Color(0xB42318));
            g.draw(new Line2D.Float(3, 4, 13, 4));
            g.draw(new Line2D.Float(6, 4, 6, 2.5f));
            g.draw(new Line2D.Float(6, 2.5f, 10, 2.5f));
            g.draw(new Line2D.Float(10, 2.5f, 10, 4));
            g.draw(new RoundRectangle2D.Float(4, 6, 8, 8, 1, 1));
        });
    }

    private static Icon copy() {
        return paint(g -> {
            g.setColor(ACCENT);
            g.draw(new RoundRectangle2D.Float(5, 2, 8, 9, 2, 2));
            g.draw(new RoundRectangle2D.Float(2, 5, 8, 9, 2, 2));
        });
    }

    private static void document(Graphics2D g, Color color) {
        g.setColor(color);
        g.draw(new RoundRectangle2D.Float(3, 1, 10, 13, 2, 2));
        g.draw(new Line2D.Float(5, 5, 11, 5));
        g.draw(new Line2D.Float(5, 8, 11, 8));
        g.draw(new Line2D.Float(5, 11, 9, 11));
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
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.translate(x, y);
                if (component != null && !component.isEnabled()) {
                    g.setComposite(AlphaComposite.SrcOver.derive(0.4f));
                }
                painter.accept(g);
                g.dispose();
            }
        };
    }
}
