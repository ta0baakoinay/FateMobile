import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Iterator;

public final class PrepareAssets {
    public static void main(String[] args) throws Exception {
        // 1) Map PNG -> JPEG at quality 0.85
        BufferedImage map = ImageIO.read(new File(args[0]));
        writeJpeg(map, args[1], 0.85f);
        System.out.println("Map JPEG: " + args[1] + " (" + new File(args[1]).length() + " bytes)");

        // 2) Sprite frame -> nearest-neighbor upscale (preserve pixel art, no blur)
        BufferedImage sprite = ImageIO.read(new File(args[2]));
        int scale = Integer.parseInt(args[4]);
        BufferedImage upscaled = new BufferedImage(sprite.getWidth() * scale, sprite.getHeight() * scale, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = upscaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(sprite, 0, 0, upscaled.getWidth(), upscaled.getHeight(), null);
        g.dispose();
        ImageIO.write(upscaled, "png", new File(args[3]));
        System.out.println("Sprite PNG: " + args[3] + " " + upscaled.getWidth() + "x" + upscaled.getHeight());
    }

    private static void writeJpeg(BufferedImage img, String path, float quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        try (FileImageOutputStream out = new FileImageOutputStream(new File(path))) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(img, null, null), param);
        }
        writer.dispose();
    }
}
