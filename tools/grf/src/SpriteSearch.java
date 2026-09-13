import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Searches GRF entries for known Korean path fragments used by player body
 * sprites. Writes results to a UTF-8 file rather than stdout — the Windows
 * console here can't render Korean, and mangled display made earlier runs
 * of this tool impossible to verify by eye.
 */
public final class SpriteSearch {
    public static void main(String[] args) throws Exception {
        String grfPath = args[0];
        String outPath = args[1];
        // Hardcoded rather than passed via argv: Windows console/JVM argv
        // encoding mangles non-ASCII arguments before Java ever sees them.
        String[] needles = {"몸통", "인간족", "초보자", "novice", "Novice"};
        // 몸통(body) 인간족(human race) 초보자(novice)
        int maxPerNeedle = args.length > 2 ? Integer.parseInt(args[2]) : 30;

        try (GrfArchive grf = GrfArchive.open(grfPath);
             PrintWriter out = new PrintWriter(java.nio.file.Files.newBufferedWriter(java.nio.file.Paths.get(outPath), StandardCharsets.UTF_8))) {
            for (String needle : needles) {
                out.println("=== needle: " + needle + " ===");
                int shown = 0;
                for (Map.Entry<String, GrfArchive.Entry> e : grf.entries().entrySet()) {
                    if (!e.getValue().isFile()) continue;
                    String name = e.getValue().name;
                    if (name.contains(needle)) {
                        out.println(name + "  (" + e.getValue().uncompressedSize + " bytes)");
                        shown++;
                        if (shown >= maxPerNeedle) break;
                    }
                }
                out.println("--- shown " + shown + " ---");
            }
        }
        System.out.println("Wrote results to " + outPath);
    }
}
