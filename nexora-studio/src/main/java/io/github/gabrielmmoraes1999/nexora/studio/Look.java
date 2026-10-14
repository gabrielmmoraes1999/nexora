package io.github.gabrielmmoraes1999.nexora.studio;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import javax.swing.plaf.FontUIResource;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class Look {

    static final Color NAVY = new Color(0x1B3A4B);
    static final Color ACCENT = new Color(0x1F6AA5);
    static final Color INK = new Color(0x1C2833);
    static final Color MUTED = new Color(0x667085);
    static final Color LINE = new Color(0xD5DDE6);
    static final Color PAPER = new Color(0xF4F7FA);
    static final Color CHROME = new Color(0xF6F8FB);
    static final Color EDITOR = new Color(0xFFFEFB);
    static final Color SELECTION = new Color(0xD6E6F5);
    static final Color ALT = new Color(0xF4F8FB);
    static final Color HEADER = new Color(0xE7EEF5);
    static final Color ERROR = new Color(0xB42318);
    static final Color KEYWORD = new Color(0x0033A0);
    static final Color STRING = new Color(0x067D17);
    static final Color COMMENT = new Color(0x8A8F98);
    static final Color QUOTED = new Color(0x8A4B08);

    static final Font UI = font("Segoe UI", Font.PLAIN, 13);
    static final Font UI_BOLD = UI.deriveFont(Font.BOLD);
    static final Font MONO = font("Consolas", Font.PLAIN, 14);

    private Look() {
    }

    static void install() {
        FlatLaf.setGlobalExtraDefaults(Map.ofEntries(
                Map.entry("@accentColor", "#1F6AA5"),
                Map.entry("@accentFocusColor", "#18588A"),
                Map.entry("Component.arc", "8"),
                Map.entry("Button.arc", "8"),
                Map.entry("TextComponent.arc", "8"),
                Map.entry("Component.focusWidth", "1"),
                Map.entry("Component.arrowType", "chevron"),
                Map.entry("ScrollBar.thumbArc", "999"),
                Map.entry("ScrollBar.trackArc", "999"),
                Map.entry("ScrollBar.width", "10"),
                Map.entry("TabbedPane.tabSelectionHeight", "3"),
                Map.entry("Tree.selectionBackground", "#D6E6F5"),
                Map.entry("Tree.selectionForeground", "#1C2833"),
                Map.entry("Tree.selectionInactiveBackground", "#E7EEF5"),
                Map.entry("Table.selectionBackground", "#D6E6F5"),
                Map.entry("Table.selectionForeground", "#1C2833"),
                Map.entry("Table.selectionInactiveBackground", "#E7EEF5"),
                Map.entry("Table.alternateRowColor", "#F4F8FB")
        ));
        UIManager.put("defaultFont", new FontUIResource(UI));
        if (!FlatLightLaf.setup()) {
            system();
        }
    }

    static JButton button(String text) {
        JButton button = new JButton(text);
        Icon icon = Icons.action(text);
        if (icon != null) {
            button.setIcon(icon);
            button.setIconTextGap(6);
        }
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.putClientProperty("JButton.buttonType", "roundRect");
        return button;
    }

    static JButton tool(String text) {
        JButton button = button(text);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setFocusable(false);
        return button;
    }

    static JButton primary(String text) {
        return primary(text, null);
    }

    static JButton primary(String text, Icon icon) {
        JButton button = new JButton(text);
        if (icon != null) {
            button.setIcon(icon);
            button.setIconTextGap(6);
        }
        button.setFont(UI_BOLD);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.putClientProperty("JButton.buttonType", "roundRect");
        if (UIManager.getLookAndFeel() instanceof FlatLaf) {
            button.putClientProperty("FlatLaf.style",
                    "background: #1F6AA5; foreground: #FFFFFF; borderWidth: 0;"
                            + "[hover]background: #18588A;"
                            + "[pressed]background: #144A74;"
                            + "[disabled]background: #E7EEF5;"
                            + "[disabled]foreground: #98A2B3");
        } else {
            button.setBackground(ACCENT);
            button.setForeground(Color.WHITE);
            button.setOpaque(true);
            button.setBorder(new EmptyBorder(6, 14, 6, 14));
            button.setFocusPainted(false);
        }
        return button;
    }

    static void band(JPanel panel) {
        panel.setOpaque(true);
        panel.setBackground(CHROME);
        panel.setBorder(new MatteBorder(0, 0, 1, 0, LINE));
    }

    static JScrollPane scroll(Component view) {
        JScrollPane pane = new JScrollPane(view);
        pane.setBorder(new LineBorder(LINE));
        pane.getViewport().setBackground(Color.WHITE);
        return pane;
    }

    static void paper(JComponent component) {
        component.setBackground(PAPER);
        component.setOpaque(true);
    }

    static BufferedImage icon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        float side = size;
        float arc = side * 0.28f;
        g.setPaint(new GradientPaint(0, 0, new Color(0x2F86C5), 0, side, new Color(0x143044)));
        g.fill(new RoundRectangle2D.Float(0, 0, side, side, arc, arc));

        float left = side * 0.22f;
        float width = side * 0.56f;
        float top = side * 0.24f;
        float ellipse = Math.max(3f, side * 0.16f);
        float body = side * 0.46f;
        g.setColor(Color.WHITE);
        g.fill(new Rectangle2D.Float(left, top + ellipse / 2f, width, body));
        g.fill(new Ellipse2D.Float(left, top + body - ellipse / 2f, width, ellipse));
        g.setColor(new Color(0xE7F3FB));
        g.fill(new Ellipse2D.Float(left, top, width, ellipse));
        g.setStroke(new java.awt.BasicStroke(Math.max(1f, side / 32f)));
        g.setColor(new Color(0x1F6AA5));
        g.draw(new Ellipse2D.Float(left, top, width, ellipse));
        if (size >= 48) {
            g.setColor(new Color(0x143044));
            g.setFont(UI.deriveFont(Font.BOLD, side * 0.2f));
            java.awt.FontMetrics metrics = g.getFontMetrics();
            float textX = (side - metrics.stringWidth("N")) / 2f;
            float capBottom = top + ellipse * 0.92f;
            float base = top + ellipse / 2f + body - ellipse * 0.2f;
            float textY = capBottom + (base - capBottom - metrics.getHeight()) / 2f + metrics.getAscent();
            g.drawString("N", textX, textY);
        } else if (size >= 24) {
            g.setColor(new Color(0xC5D9E8));
            g.draw(new Ellipse2D.Float(left, top + body * 0.42f, width, ellipse));
        }
        g.dispose();
        return image;
    }

    static List<Image> iconImages() {
        List<Image> images = new ArrayList<>();
        for (int size : new int[]{16, 20, 24, 32, 40, 48, 64, 128, 256}) {
            images.add(icon(size));
        }
        return List.copyOf(images);
    }

    static ImageIcon imageIcon(int size) {
        return new ImageIcon(new BaseMultiResolutionImage(icon(size), icon(size * 2)));
    }

    private static void system() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // o visual do sistema fica como o Java escolheu
        }
    }

    private static Font font(String name, int style, int size) {
        Font candidate = new Font(name, style, size);
        if (name.equalsIgnoreCase(candidate.getFamily())) {
            return candidate;
        }
        if ("Consolas".equals(name)) {
            return new Font(Font.MONOSPACED, style, size);
        }
        return new Font(Font.SANS_SERIF, style, size);
    }
}
