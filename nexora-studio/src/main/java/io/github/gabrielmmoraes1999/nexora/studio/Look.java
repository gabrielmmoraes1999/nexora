package io.github.gabrielmmoraes1999.nexora.studio;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

final class Look {

    static final Color NAVY = new Color(0x1B3A4B);
    static final Color ACCENT = new Color(0x1F6AA5);
    static final Color INK = new Color(0x1C2833);
    static final Color MUTED = new Color(0x667085);
    static final Color LINE = new Color(0xD5DDE6);
    static final Color PAPER = new Color(0xF4F7FA);
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
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // o visual do sistema fica como o Java escolheu
        }
        List<Object> keys = new ArrayList<>();
        Enumeration<?> pending = UIManager.getDefaults().keys();
        while (pending.hasMoreElements()) {
            keys.add(pending.nextElement());
        }
        for (Object key : keys) {
            if (UIManager.get(key) instanceof Font) {
                UIManager.put(key, UI);
            }
        }
    }

    static JButton button(String text) {
        JButton button = new JButton(text);
        button.setFont(UI);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    static JButton primary(String text) {
        JButton button = new JButton(text);
        button.setUI(new BasicButtonUI());
        button.setFont(UI_BOLD);
        button.setForeground(Color.WHITE);
        button.setBackground(ACCENT);
        button.setOpaque(true);
        button.setBorder(new EmptyBorder(6, 14, 6, 14));
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
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

    static BufferedImage iconImage() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(NAVY);
        graphics.fillRoundRect(2, 2, 28, 28, 8, 8);
        graphics.setColor(Color.WHITE);
        graphics.fillRoundRect(8, 9, 16, 14, 4, 4);
        graphics.setColor(ACCENT);
        graphics.fillOval(11, 7, 10, 6);
        graphics.fillRect(11, 9, 10, 3);
        graphics.dispose();
        return image;
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
