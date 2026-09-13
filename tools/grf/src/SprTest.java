import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class SprTest {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(args[0]));
        if (args.length > 2 && args[2].equals("diag")) {
            diag(data);
            return;
        }
        SprFile spr = SprFile.parse(data);
        System.out.println("version=" + spr.versionMajor + "." + spr.versionMinor + " frames=" + spr.frames.size()
                + " palette=" + (spr.palette != null ? "present" : "MISSING"));
        for (int i = 0; i < Math.min(3, spr.frames.size()); i++) {
            SprFile.Frame f = spr.frames.get(i);
            System.out.println("  frame[" + i + "] " + f.width + "x" + f.height);
        }

        // Render frame 0 as a PNG using the palette (index 0 = transparent, per RO convention).
        if (args.length > 1 && spr.palette != null) {
            SprFile.Frame f = spr.frames.get(0);
            BufferedImage img = new BufferedImage(f.width, f.height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < f.height; y++) {
                for (int x = 0; x < f.width; x++) {
                    int idx = f.indices[y * f.width + x] & 0xFF;
                    int argb;
                    if (idx == 0) {
                        argb = 0; // transparent
                    } else {
                        int r = spr.palette[idx * 4] & 0xFF;
                        int g = spr.palette[idx * 4 + 1] & 0xFF;
                        int b = spr.palette[idx * 4 + 2] & 0xFF;
                        argb = (0xFF << 24) | (r << 16) | (g << 8) | b;
                    }
                    img.setRGB(x, y, argb);
                }
            }
            ImageIO.write(img, "png", new File(args[1]));
            System.out.println("Wrote " + args[1]);
        }
    }

    private static void diag(byte[] data) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.get(); buf.get(); // "SP"
        int minor = buf.get() & 0xFF;
        int major = buf.get() & 0xFF;
        int numPal = buf.getShort() & 0xFFFF;
        int numRgba = buf.getShort() & 0xFFFF;
        System.out.println("ver=" + major + "." + minor + " numPal=" + numPal + " numRgba=" + numRgba + " fileLen=" + data.length);
        for (int i = 0; i < Math.min(numPal, 6); i++) {
            int pos = buf.position();
            int width = buf.getShort() & 0xFFFF;
            int height = buf.getShort() & 0xFFFF;
            int rawSize = width * height;
            System.out.println("frame " + i + " @pos=" + pos + " w=" + width + " h=" + height + " rawSize=" + rawSize + " remaining=" + buf.remaining());
            if (buf.remaining() < rawSize) {
                System.out.println("  -> would underflow if read raw; stopping diag here");
                // dump next 16 bytes for inspection
                byte[] peek = new byte[Math.min(32, buf.remaining())];
                buf.get(peek);
                StringBuilder sb = new StringBuilder();
                for (byte b : peek) sb.append(String.format("%02X ", b));
                System.out.println("  next bytes: " + sb);
                return;
            }
            buf.position(buf.position() + rawSize);
        }
    }
}
