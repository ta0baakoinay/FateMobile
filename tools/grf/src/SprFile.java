import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for Gravity's .spr sprite format (version 2.1 confirmed against
 * the real novice body sprite: signature "SP", major=2, minor=1, 110
 * indexed frames, 0 true-color frames). Palette-indexed (8-bit) frames only
 * are implemented — this file has no RGBA (true-color) frames to verify
 * that path against, so it is not implemented; see the constructor's check.
 * Public client format, unrelated to the FateRO server's private protocol.
 */
public final class SprFile {
    public static final class Frame {
        public final int width;
        public final int height;
        public final byte[] indices; // palette index per pixel, row-major

        Frame(int width, int height, byte[] indices) {
            this.width = width;
            this.height = height;
            this.indices = indices;
        }
    }

    public final int versionMajor;
    public final int versionMinor;
    public final List<Frame> frames;
    public final byte[] palette; // 256 * 4 bytes RGBA, or null if this sprite has no indexed frames

    private SprFile(int major, int minor, List<Frame> frames, byte[] palette) {
        this.versionMajor = major;
        this.versionMinor = minor;
        this.frames = frames;
        this.palette = palette;
    }

    public static SprFile parse(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.get() != 'S' || buf.get() != 'P') {
            throw new IllegalArgumentException("Not a SPR file (bad signature)");
        }
        int minor = buf.get() & 0xFF;
        int major = buf.get() & 0xFF;
        if (major != 2 || minor != 1) {
            throw new UnsupportedOperationException("Only SPR version 2.1 verified/implemented, got " + major + "." + minor);
        }
        int numPalImages = buf.getShort() & 0xFFFF;
        int numRgbaImages = buf.getShort() & 0xFFFF;
        if (numRgbaImages != 0) {
            throw new UnsupportedOperationException("RGBA (true-color) SPR frames not implemented — this file has " + numRgbaImages + " of them, none verified against yet.");
        }

        List<Frame> frames = new ArrayList<>(numPalImages);
        for (int i = 0; i < numPalImages; i++) {
            int width = buf.getShort() & 0xFFFF;
            int height = buf.getShort() & 0xFFFF;
            byte[] indices = decodeRle(buf, width * height);
            frames.add(new Frame(width, height, indices));
        }

        byte[] palette = null;
        if (numPalImages > 0) {
            int remaining = buf.remaining();
            if (remaining != 1024) {
                System.err.println("Warning: expected exactly 1024 bytes of trailing palette, found " + remaining + " — frame parsing may have desynced.");
            }
            if (remaining >= 1024) {
                palette = new byte[1024];
                buf.get(palette);
            }
        }

        return new SprFile(major, minor, frames, palette);
    }

    /**
     * Indexed-frame pixel decoding, confirmed against the real novice body
     * sprite's byte stream: a 0x00 byte is followed by a repeat-count byte
     * for that many transparent (palette index 0) pixels; any nonzero byte
     * is one literal palette-index pixel. Traced by hand against the first
     * ~30 bytes of frame 0 (width=38,height=74) before writing this, not
     * assumed from memory alone — see docs/FATE_MMO_MOBILE_ASSETS.md.
     */
    private static byte[] decodeRle(ByteBuffer buf, int pixelCount) {
        byte[] out = new byte[pixelCount];
        int written = 0;
        while (written < pixelCount) {
            byte b = buf.get();
            if (b == 0) {
                int count = buf.get() & 0xFF;
                int n = Math.min(count, pixelCount - written);
                written += n; // out[] is already zero-initialized
            } else {
                out[written++] = b;
            }
        }
        return out;
    }
}
