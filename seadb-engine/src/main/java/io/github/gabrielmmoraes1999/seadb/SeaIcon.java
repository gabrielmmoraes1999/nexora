package io.github.gabrielmmoraes1999.seadb;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;

/** SeaDB sea lion mark, drawn at every resolution for windows and packaging. */
public final class SeaIcon {
    private SeaIcon() {}

    public static BufferedImage image(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 128.0, size / 128.0);
        g.setPaint(new GradientPaint(0, 0, new Color(0x174E75), 128, 128, new Color(0x061C32)));
        g.fill(new RoundRectangle2D.Double(0, 0, 128, 128, 30, 30));
        // Upright head, long neck and sweeping tail echo the reference sea lion.
        Path2D body = new Path2D.Double();
        body.moveTo(12, 99); body.curveTo(37, 92, 50, 80, 59, 57);
        body.curveTo(63, 43, 63, 26, 77, 20); body.curveTo(84, 16, 92, 20, 98, 16);
        body.curveTo(106, 12, 114, 18, 110, 26); body.curveTo(108, 33, 96, 35, 93, 43);
        body.curveTo(87, 57, 102, 75, 86, 96); body.curveTo(85, 102, 98, 101, 102, 108);
        body.curveTo(81, 112, 72, 107, 74, 96); body.curveTo(56, 105, 31, 112, 12, 105);
        body.lineTo(20, 101); body.closePath();
        g.setPaint(new GradientPaint(65, 20, new Color(0xDBEEFA), 55, 111, new Color(0x739ABF)));
        g.fill(body);
        g.setColor(new Color(0x123D60));
        Path2D flipper = new Path2D.Double();
        flipper.moveTo(77, 57); flipper.curveTo(84, 78, 78, 100, 49, 108);
        flipper.curveTo(65, 95, 66, 77, 77, 57); g.fill(flipper);
        g.fill(new Ellipse2D.Double(84, 25, 5, 5));
        g.fill(new Ellipse2D.Double(103, 17, 8, 6));
        g.setColor(Color.WHITE); g.fill(new Ellipse2D.Double(85, 25, 1.7, 1.7));
        if (size >= 32) {
            g.setColor(new Color(0x214E70)); g.setStroke(new BasicStroke(1.4f));
            g.draw(new Line2D.Double(101, 30, 114, 37));
            g.draw(new Line2D.Double(100, 32, 111, 42));
            g.draw(new Line2D.Double(98, 33, 106, 45));
        }
        g.dispose();
        return image;
    }
}
