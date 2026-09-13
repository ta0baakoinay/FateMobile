import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Composites a .gnd ground mesh's top faces into one flat top-down image,
 * using each cube's actual UV-sampled region of its real ground texture.
 * This is a deliberate simplification for a 2D mobile client: it takes the
 * axis-aligned bounding box of each surface's UV quad (ignoring any
 * rotation baked into vertex order), so a minority of tiles may render
 * mirrored/rotated relative to the true 3D client — a known, documented
 * trade-off, not a silent inaccuracy. No 3D geometry (walls, height,
 * models/props from the companion .rsw) is rendered; this is ground-plane
 * texture only, matching the roadmap's "flat map, geometry from GAT for
 * walkability" scope for a first pass.
 */
public final class MapRasterizer {
    public static void main(String[] args) throws Exception {
        String gndPath = args[0];
        String dataGrfPath = args[1];
        String fateGrfPath = args[2];
        String outPath = args[3];
        int tileSize = args.length > 4 ? Integer.parseInt(args[4]) : 32;

        GndFile gnd = GndFile.parse(Files.readAllBytes(Paths.get(gndPath)));

        Map<Integer, BufferedImage> textureCache = new HashMap<>();
        try (GrfArchive dataGrf = GrfArchive.open(dataGrfPath); GrfArchive fateGrf = GrfArchive.open(fateGrfPath)) {
            BufferedImage out = new BufferedImage(gnd.width * tileSize, gnd.height * tileSize, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(java.awt.Color.BLACK);
            g.fillRect(0, 0, out.getWidth(), out.getHeight());

            int drawn = 0, skipped = 0;
            for (int y = 0; y < gnd.height; y++) {
                for (int x = 0; x < gnd.width; x++) {
                    GndFile.Cube cube = gnd.cubeAt(x, y);
                    if (cube.tileUp < 0) {
                        skipped++;
                        continue;
                    }
                    GndFile.Surface surface = gnd.surfaces[cube.tileUp];
                    BufferedImage tex = textureCache.computeIfAbsent(surface.textureIndex, idx -> {
                        try {
                            String texName = gnd.textures[idx];
                            String path = "data\\texture\\" + texName;
                            byte[] bytes = fateGrf.contains(path) ? fateGrf.extract(path) : dataGrf.extract(path);
                            return ImageIO.read(new ByteArrayInputStream(bytes));
                        } catch (Exception e) {
                            System.err.println("Failed to load texture index " + idx + ": " + e.getMessage());
                            return null;
                        }
                    });
                    if (tex == null) {
                        skipped++;
                        continue;
                    }

                    float uMin = min4(surface.u), uMax = max4(surface.u);
                    float vMin = min4(surface.v), vMax = max4(surface.v);
                    int sx0 = clamp((int) Math.round(uMin * tex.getWidth()), 0, tex.getWidth());
                    int sx1 = clamp((int) Math.round(uMax * tex.getWidth()), 0, tex.getWidth());
                    int sy0 = clamp((int) Math.round(vMin * tex.getHeight()), 0, tex.getHeight());
                    int sy1 = clamp((int) Math.round(vMax * tex.getHeight()), 0, tex.getHeight());
                    if (sx1 <= sx0) sx1 = sx0 + 1;
                    if (sy1 <= sy0) sy1 = sy0 + 1;

                    int dx0 = x * tileSize, dy0 = y * tileSize;
                    g.drawImage(tex, dx0, dy0, dx0 + tileSize, dy0 + tileSize, sx0, sy0, sx1, sy1, null);
                    drawn++;
                }
            }
            g.dispose();
            ImageIO.write(out, "png", new File(outPath));
            System.out.println("Rasterized " + drawn + " cubes, skipped " + skipped + " (no top face/texture). Output: " + outPath
                    + " (" + out.getWidth() + "x" + out.getHeight() + ")");
        }
    }

    private static float min4(float[] a) { float m = a[0]; for (float v : a) m = Math.min(m, v); return m; }
    private static float max4(float[] a) { float m = a[0]; for (float v : a) m = Math.max(m, v); return m; }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
