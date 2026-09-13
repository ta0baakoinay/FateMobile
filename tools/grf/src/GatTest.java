import java.nio.file.Files;
import java.nio.file.Paths;

public final class GatTest {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(args[0]));
        GatFile gat = GatFile.parse(data);
        System.out.println("width=" + gat.width + " height=" + gat.height);
        int[] counts = new int[8];
        for (int t : gat.type) {
            if (t >= 0 && t < counts.length) counts[t]++;
        }
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) System.out.println("type " + i + ": " + counts[i] + " cells");
        }
        // sample a middle cell's heights
        int mx = gat.width / 2, my = gat.height / 2;
        int idx = my * gat.width + mx;
        System.out.println("center cell (" + mx + "," + my + ") type=" + gat.type[idx]
                + " heights=" + gat.heights[idx*4] + "," + gat.heights[idx*4+1] + "," + gat.heights[idx*4+2] + "," + gat.heights[idx*4+3]
                + " walkable=" + gat.isWalkable(mx, my));
    }
}
