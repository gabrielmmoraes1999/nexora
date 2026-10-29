import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the SeaDB mark as a Windows {@code .ico}.
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
        return io.github.gabrielmmoraes1999.seadb.SeaIcon.image(size);
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
