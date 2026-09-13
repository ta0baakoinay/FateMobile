import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Diagnostic ACT walker — prints structure as it parses so byte-layout guesses can be checked against file length. */
public final class ActDiag {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(args[0]));
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.get(); buf.get(); // "AC"
        int minor = buf.get() & 0xFF;
        int major = buf.get() & 0xFF;
        int numActions = buf.getShort() & 0xFFFF; // NOTE: trying uint16 here instead of uint32 based on hex dump spacing
        System.out.println("ver=" + major + "." + minor + " numActions=" + numActions + " fileLen=" + data.length + " posAfterHeaderGuess=" + buf.position());
    }
}
