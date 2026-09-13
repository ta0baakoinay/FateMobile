import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

public final class Downscale {
    public static void main(String[] args) throws Exception {
        BufferedImage src = ImageIO.read(new File(args[0]));
        int targetW = Integer.parseInt(args[2]);
        int targetH = (int) ((long) src.getHeight() * targetW / src.getWidth());
        BufferedImage out = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, targetW, targetH, null);
        g.dispose();
        ImageIO.write(out, "png", new File(args[1]));
        System.out.println(src.getWidth() + "x" + src.getHeight() + " -> " + targetW + "x" + targetH);
    }
}
