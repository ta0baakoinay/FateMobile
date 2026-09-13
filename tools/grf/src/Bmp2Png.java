import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public final class Bmp2Png {
    public static void main(String[] args) throws Exception {
        BufferedImage img = ImageIO.read(new File(args[0]));
        if (img == null) {
            System.err.println("ImageIO could not decode: " + args[0]);
            System.exit(1);
        }
        System.out.println("Decoded " + args[0] + ": " + img.getWidth() + "x" + img.getHeight());
        ImageIO.write(img, "png", new File(args[1]));
    }
}
