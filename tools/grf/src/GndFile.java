import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;

/**
 * Parser for Gravity's .gnd ground-mesh format (version 1.7 confirmed).
 * Every struct size here (lightmap entry = 256 bytes, surface = 40 bytes,
 * cube = 28 bytes) was confirmed by solving the real prontera.gnd's exact
 * file size arithmetically, not assumed from a remembered spec — see
 * docs/FATE_MMO_MOBILE_ASSETS.md for the derivation. Public client format,
 * unrelated to the FateRO server's private protocol.
 */
public final class GndFile {
    public final int width;
    public final int height;
    public final float zoom;
    public final String[] textures; // relative to data\texture\
    public final Surface[] surfaces;
    public final Cube[] cubes; // width*height, row-major

    public static final class Surface {
        public final float[] u;
        public final float[] v;
        public final int textureIndex;
        public final int lightmapIndex;
        public final int[] color; // r,g,b,a (0-255)

        Surface(float[] u, float[] v, int textureIndex, int lightmapIndex, int[] color) {
            this.u = u;
            this.v = v;
            this.textureIndex = textureIndex;
            this.lightmapIndex = lightmapIndex;
            this.color = color;
        }
    }

    public static final class Cube {
        public final float[] height = new float[4]; // bottom-left, bottom-right, top-left, top-right
        public final int tileUp;
        public final int tileSide;
        public final int tileFront;

        Cube(float[] h, int up, int side, int front) {
            System.arraycopy(h, 0, this.height, 0, 4);
            this.tileUp = up;
            this.tileSide = side;
            this.tileFront = front;
        }
    }

    private GndFile(int width, int height, float zoom, String[] textures, Surface[] surfaces, Cube[] cubes) {
        this.width = width;
        this.height = height;
        this.zoom = zoom;
        this.textures = textures;
        this.surfaces = surfaces;
        this.cubes = cubes;
    }

    public Cube cubeAt(int x, int y) {
        return cubes[y * width + x];
    }

    public static GndFile parse(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        byte[] sig = new byte[4];
        buf.get(sig);
        if (sig[0] != 'G' || sig[1] != 'R' || sig[2] != 'G' || sig[3] != 'N') {
            throw new IllegalArgumentException("Not a GND file (bad signature)");
        }
        int major = buf.get() & 0xFF;
        int minor = buf.get() & 0xFF;
        int width = buf.getInt();
        int height = buf.getInt();
        float zoom = buf.getFloat();
        int textureCount = buf.getInt();
        int textureNameLength = buf.getInt();

        String[] textures = new String[textureCount];
        Charset eucKr = Charset.forName("EUC-KR");
        for (int i = 0; i < textureCount; i++) {
            byte[] nameBuf = new byte[textureNameLength];
            buf.get(nameBuf);
            int len = 0;
            while (len < nameBuf.length && nameBuf[len] != 0) len++;
            textures[i] = new String(nameBuf, 0, len, eucKr);
        }

        int lightmapCount = buf.getInt();
        int cellPerCellX = buf.getInt();
        int cellPerCellY = buf.getInt();
        int gridSizeCellField = buf.getInt(); // present in the format; not needed for layout (see class doc)
        int lightmapEntrySize = cellPerCellX * cellPerCellY * 4; // confirmed empirically, see docs/FATE_MMO_MOBILE_ASSETS.md
        buf.position(buf.position() + lightmapCount * lightmapEntrySize); // skip — cosmetic shading only, not needed for texture identity

        int surfaceCount = buf.getInt();
        Surface[] surfaces = new Surface[surfaceCount];
        for (int i = 0; i < surfaceCount; i++) {
            float[] u = new float[4];
            float[] v = new float[4];
            for (int c = 0; c < 4; c++) u[c] = buf.getFloat();
            for (int c = 0; c < 4; c++) v[c] = buf.getFloat();
            int textureIndex = buf.getShort();
            int lightmapIndex = buf.getShort();
            int[] color = new int[4];
            for (int c = 0; c < 4; c++) color[c] = buf.get() & 0xFF;
            surfaces[i] = new Surface(u, v, textureIndex, lightmapIndex, color);
        }

        Cube[] cubes = new Cube[width * height];
        float[] h = new float[4];
        for (int i = 0; i < width * height; i++) {
            h[0] = buf.getFloat();
            h[1] = buf.getFloat();
            h[2] = buf.getFloat();
            h[3] = buf.getFloat();
            int up = buf.getInt();
            int side = buf.getInt();
            int front = buf.getInt();
            cubes[i] = new Cube(h, up, side, front);
        }

        if (buf.remaining() != 0) {
            System.err.println("Warning: " + buf.remaining() + " unexpected trailing bytes after parsing GND — struct layout may not hold for this file's exact version.");
        }

        return new GndFile(width, height, zoom, textures, surfaces, cubes);
    }
}
