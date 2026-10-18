import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the Nexora mark as a Windows {@code .ico}.
 * The drawing matches the studio window icon.
 */
public final class IconWriter {

    private IconWriter() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1 || args[0].isBlank()) {
            System.err.println("usage: IconWriter <file.ico>");
            System.exit(1);
        }
        int[] sizes = {16, 20, 24, 32, 40, 48, 64, 128, 256};
        List<byte[]> images = new ArrayList<>();
        for (int size : sizes) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!ImageIO.write(icon(size), "png", png)) {
                throw new IOException("PNG writer is not available");
            }
            images.add(png.toByteArray());
        }
        Files.write(Path.of(args[0]), ico(images, sizes));
    }

    private static BufferedImage icon(int size) {
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
        g.setStroke(new BasicStroke(Math.max(1f, side / 32f)));
        g.setColor(new Color(0x1F6AA5));
        g.draw(new Ellipse2D.Float(left, top, width, ellipse));
        if (size >= 48) {
            g.setColor(new Color(0x143044));
            g.setFont(new Font("Segoe UI", Font.BOLD, 1).deriveFont(side * 0.2f));
            FontMetrics metrics = g.getFontMetrics();
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

    private static byte[] ico(List<byte[]> pngs, int[] sizes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int count = pngs.size();
        int offset = 6 + 16 * count;
        write16(out, 0);
        write16(out, 1);
        write16(out, count);
        for (int i = 0; i < count; i++) {
            int dim = sizes[i] >= 256 ? 0 : sizes[i];
            out.write(dim);
            out.write(dim);
            out.write(0);
            out.write(0);
            write16(out, 1);
            write16(out, 32);
            write32(out, pngs.get(i).length);
            write32(out, offset);
            offset += pngs.get(i).length;
        }
        for (byte[] png : pngs) {
            out.write(png);
        }
        return out.toByteArray();
    }

    private static void write16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void write32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }
}
