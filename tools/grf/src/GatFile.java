import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Parser for Gravity's .gat ground/walkability format. Verified against the
 * real prontera.gat extracted from F:\FateMMO\data.grf: header fields plus
 * width*height*20-byte cell records account for the file's exact byte size
 * (2,446,094 bytes for a 312x392 map), confirming this layout rather than
 * assuming it from memory. Public, well-documented client format, unrelated
 * to the FateRO server's private protocol.
 */
public final class GatFile {
    public final int width;
    public final int height;
    public final float[] heights; // 4 per cell: bottomLeft, bottomRight, topLeft, topRight
    public final int[] type; // per-cell walkability/terrain type

    private GatFile(int width, int height, float[] heights, int[] type) {
        this.width = width;
        this.height = height;
        this.heights = heights;
        this.type = type;
    }

    /** Common convention: type 0 (walkable ground) and 3 (walkable water) are passable. */
    public boolean isWalkable(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return false;
        int t = type[y * width + x];
        return t == 0 || t == 3;
    }

    public static GatFile parse(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        byte[] sig = new byte[4];
        buf.get(sig);
        if (sig[0] != 'G' || sig[1] != 'R' || sig[2] != 'A' || sig[3] != 'T') {
            throw new IllegalArgumentException("Not a GAT file (bad signature)");
        }
        int major = buf.get() & 0xFF;
        int minor = buf.get() & 0xFF;
        int width = buf.getInt();
        int height = buf.getInt();

        long expectedSize = 14L + (long) width * height * 20;
        if (expectedSize != data.length) {
            throw new IllegalStateException("GAT size mismatch: header implies " + expectedSize + " bytes, file is " + data.length + " (version " + major + "." + minor + ")");
        }

        float[] heights = new float[width * height * 4];
        int[] type = new int[width * height];
        for (int i = 0; i < width * height; i++) {
            heights[i * 4] = buf.getFloat();
            heights[i * 4 + 1] = buf.getFloat();
            heights[i * 4 + 2] = buf.getFloat();
            heights[i * 4 + 3] = buf.getFloat();
            type[i] = buf.getInt();
        }
        return new GatFile(width, height, heights, type);
    }
}
