import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reader for Gravity's GRF archive format (version 0x200), as used by the
 * Ragnarok Online client. Verified against the real Fate MMO client files
 * (F:\FateMMO\Fate.grf etc.) rather than implemented from memory alone:
 * the header layout, magic signature, file-table offset, and version field
 * were all read back from the actual file bytes and cross-checked before
 * writing this parser. See docs/FATE_MMO_MOBILE_ASSETS.md for the derivation.
 *
 * This is a public, well-documented archive format (used by many
 * independent open-source RO tools — GRF Editor, BrowEdit, rAthena's own
 * grfio.c) and is unrelated to the FateRO server's private protocol; no
 * server source was needed to write this, only the client's own files.
 */
public final class GrfArchive implements AutoCloseable {

    private static final byte[] SIGNATURE = "Master of Magic".getBytes(Charset.forName("US-ASCII"));
    private static final int HEADER_SIZE = 46; // 16 (signature) + 14 (reserved) + 4*4 (offset/seed/count/version)
    private static final int SUPPORTED_VERSION = 0x200;

    public static final class Entry {
        public final String name;
        public final int compressedSize;
        public final int compressedSizeAligned;
        public final int uncompressedSize;
        public final int flags;
        public final long dataOffset; // absolute offset in the file

        Entry(String name, int compressedSize, int compressedSizeAligned, int uncompressedSize, int flags, long dataOffset) {
            this.name = name;
            this.compressedSize = compressedSize;
            this.compressedSizeAligned = compressedSizeAligned;
            this.uncompressedSize = uncompressedSize;
            this.flags = flags;
            this.dataOffset = dataOffset;
        }

        /** Bit 0x01 = this entry is a real file (vs. a directory placeholder entry). */
        public boolean isFile() {
            return (flags & 0x01) != 0;
        }
    }

    private final RandomAccessFile raf;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private GrfArchive(RandomAccessFile raf) {
        this.raf = raf;
    }

    public static GrfArchive open(String path) throws IOException, DataFormatException {
        RandomAccessFile raf = new RandomAccessFile(path, "r");
        GrfArchive grf = new GrfArchive(raf);
        grf.readHeaderAndTable();
        return grf;
    }

    public Map<String, Entry> entries() {
        return entries;
    }

    public byte[] extract(Entry entry) throws IOException, DataFormatException {
        byte[] compressed = new byte[entry.compressedSizeAligned];
        raf.seek(entry.dataOffset);
        raf.readFully(compressed);

        if ((entry.flags & 0x04) != 0 || (entry.flags & 0x02) != 0) {
            // 0x02 = DES-encrypted (full or per-block "GRF encryption" for certain
            // file extensions in older clients). Not implemented — no encrypted
            // entries are needed for the maps/sprites this pipeline currently
            // targets, and faking a decrypt would silently corrupt output.
            throw new UnsupportedOperationException("Entry '" + entry.name + "' is encrypted (flags=0x" + Integer.toHexString(entry.flags) + "); decryption not implemented.");
        }

        byte[] out = new byte[entry.uncompressedSize];
        if (entry.uncompressedSize == 0) {
            return out;
        }
        Inflater inflater = new Inflater();
        inflater.setInput(compressed, 0, entry.compressedSize);
        int written = 0;
        try {
            while (!inflater.finished() && written < out.length) {
                int n = inflater.inflate(out, written, out.length - written);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break;
                }
                written += n;
            }
        } finally {
            inflater.end();
        }
        if (written != out.length) {
            throw new IOException("Inflate produced " + written + " bytes, expected " + out.length + " for '" + entry.name + "'");
        }
        return out;
    }

    public byte[] extract(String name) throws IOException, DataFormatException {
        Entry e = entries.get(normalize(name));
        if (e == null) {
            throw new IOException("Not found in GRF: " + name);
        }
        return extract(e);
    }

    public boolean contains(String name) {
        return entries.containsKey(normalize(name));
    }

    private static String normalize(String name) {
        return name.replace('/', '\\').toLowerCase();
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }

    private void readHeaderAndTable() throws IOException, DataFormatException {
        byte[] header = new byte[HEADER_SIZE];
        raf.seek(0);
        raf.readFully(header);

        for (int i = 0; i < SIGNATURE.length; i++) {
            if (header[i] != SIGNATURE[i]) {
                throw new IOException("Not a GRF file (bad signature)");
            }
        }

        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(30); // skip signature(16) + reserved(14)
        long fileTableOffsetRel = buf.getInt() & 0xFFFFFFFFL;
        long count1 = buf.getInt() & 0xFFFFFFFFL;
        long count2 = buf.getInt() & 0xFFFFFFFFL;
        int version = buf.getInt();

        if (version != SUPPORTED_VERSION) {
            throw new IOException("Unsupported GRF version 0x" + Integer.toHexString(version) + " (only 0x200 implemented)");
        }

        long fileCount = count2 - count1 - 7;
        long tableAbsOffset = HEADER_SIZE + fileTableOffsetRel;

        raf.seek(tableAbsOffset);
        byte[] tableSizes = new byte[8];
        raf.readFully(tableSizes);
        ByteBuffer sizesBuf = ByteBuffer.wrap(tableSizes).order(ByteOrder.LITTLE_ENDIAN);
        int tableCompressedSize = sizesBuf.getInt();
        int tableUncompressedSize = sizesBuf.getInt();

        byte[] tableCompressed = new byte[tableCompressedSize];
        raf.readFully(tableCompressed);

        byte[] table = new byte[tableUncompressedSize];
        Inflater inflater = new Inflater();
        inflater.setInput(tableCompressed);
        int written = 0;
        try {
            while (!inflater.finished() && written < table.length) {
                int n = inflater.inflate(table, written, table.length - written);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                written += n;
            }
        } finally {
            inflater.end();
        }
        if (written != table.length) {
            throw new IOException("File table inflate produced " + written + " bytes, expected " + table.length);
        }

        parseTable(table, fileCount);
    }

    private void parseTable(byte[] table, long expectedCount) {
        int pos = 0;
        long parsed = 0;
        while (pos < table.length) {
            int nameStart = pos;
            while (pos < table.length && table[pos] != 0) pos++;
            if (pos >= table.length) break; // trailing padding, not a full entry
            String name = new String(table, nameStart, pos - nameStart, Charset.forName("EUC-KR"));
            pos++; // skip null terminator

            if (pos + 17 > table.length) break;
            ByteBuffer buf = ByteBuffer.wrap(table, pos, 17).order(ByteOrder.LITTLE_ENDIAN);
            int compressedSize = buf.getInt();
            int compressedSizeAligned = buf.getInt();
            int uncompressedSize = buf.getInt();
            int flags = buf.get() & 0xFF;
            long dataOffsetRel = buf.getInt() & 0xFFFFFFFFL;
            pos += 17;

            long dataOffsetAbs = HEADER_SIZE + dataOffsetRel;
            entries.put(normalize(name), new Entry(name, compressedSize, compressedSizeAligned, uncompressedSize, flags, dataOffsetAbs));
            parsed++;
        }
        if (parsed != expectedCount) {
            System.err.println("Warning: parsed " + parsed + " entries, header declared " + expectedCount + " — table layout assumption may be off for this GRF variant.");
        }
    }
}
