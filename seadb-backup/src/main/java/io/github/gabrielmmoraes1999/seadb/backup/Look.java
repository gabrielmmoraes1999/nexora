package io.github.gabrielmmoraes1999.seadb.backup;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
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
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class Look {

    static final Color NAVY = new Color(0x1B3A4B);
    static final Color INK = new Color(0x1C2833);
    static final Color MUTED = new Color(0x667085);
    static final Color LINE = new Color(0xD5DDE6);
    static final Color PAPER = new Color(0xF4F7FA);
    static final Color CHROME = new Color(0xF6F8FB);
    static final Color ERROR = new Color(0xB42318);
    static final Color OK = new Color(0x067647);

    static final Font UI = font("Segoe UI", Font.PLAIN, 13);
    static final Font UI_BOLD = UI.deriveFont(Font.BOLD);
    static final Font MONO = font("Consolas", Font.PLAIN, 13);

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
                Map.entry("ScrollBar.width", "10")
        ));
        UIManager.put("defaultFont", new FontUIResource(UI));
        if (!FlatLightLaf.setup()) {
            system();
        }
    }

    static JButton button(String text) {
        JButton button = new JButton(text);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.putClientProperty("JButton.buttonType", "roundRect");
        return button;
    }

    static JButton primary(String text) {
        JButton button = new JButton(text);
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
            button.setBackground(new Color(0x1F6AA5));
            button.setForeground(Color.WHITE);
            button.setOpaque(true);
            button.setBorder(new EmptyBorder(6, 14, 6, 14));
            button.setFocusPainted(false);
        }
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

    static List<Image> iconImages() {
        List<Image> images = new ArrayList<>();
        for (int size : new int[]{16, 20, 24, 32, 40, 48, 64, 128, 256}) {
            images.add(icon(size));
        }
        return List.copyOf(images);
    }

    private static BufferedImage icon(int size) {
        return io.github.gabrielmmoraes1999.seadb.SeaIcon.image(size);
    }

    private static void system() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // the system look stays as Java chose it
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
