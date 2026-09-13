import java.nio.file.Files;
import java.nio.file.Paths;

public final class GndTest {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(args[0]));
        GndFile gnd = GndFile.parse(data);
        System.out.println("width=" + gnd.width + " height=" + gnd.height + " zoom=" + gnd.zoom);
        System.out.println("textures=" + gnd.textures.length);
        for (int i = 0; i < Math.min(5, gnd.textures.length); i++) {
            System.out.println("  [" + i + "] " + gnd.textures[i]);
        }
        System.out.println("surfaces=" + gnd.surfaces.length);
        System.out.println("cubes=" + gnd.cubes.length + " (expected " + (gnd.width * gnd.height) + ")");

        int mx = gnd.width / 2, my = gnd.height / 2;
        GndFile.Cube c = gnd.cubeAt(mx, my);
        System.out.println("center cube (" + mx + "," + my + ") heights=" + c.height[0] + "," + c.height[1] + "," + c.height[2] + "," + c.height[3]
                + " tileUp=" + c.tileUp);
        if (c.tileUp >= 0) {
            GndFile.Surface s = gnd.surfaces[c.tileUp];
            System.out.println("  surface textureIndex=" + s.textureIndex + " -> " + gnd.textures[s.textureIndex]);
            System.out.println("  u=" + java.util.Arrays.toString(s.u) + " v=" + java.util.Arrays.toString(s.v));
        }

        // count how many cubes have a top face at all
        int withTop = 0;
        for (GndFile.Cube cube : gnd.cubes) if (cube.tileUp >= 0) withTop++;
        System.out.println("cubes with visible top face: " + withTop + " / " + gnd.cubes.length);
    }
}
