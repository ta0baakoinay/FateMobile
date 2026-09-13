import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class SpriteSearch2 {
    public static void main(String[] args) throws Exception {
        String grfPath = args[0];
        String outPath = args[1];
        String needA = "몸통";
        String needB = "초보자";

        try (GrfArchive grf = GrfArchive.open(grfPath);
             PrintWriter out = new PrintWriter(java.nio.file.Files.newBufferedWriter(java.nio.file.Paths.get(outPath), StandardCharsets.UTF_8))) {
            for (Map.Entry<String, GrfArchive.Entry> e : grf.entries().entrySet()) {
                if (!e.getValue().isFile()) continue;
                String name = e.getValue().name;
                if (name.contains(needA) && name.contains(needB)) {
                    out.println(name + "  (" + e.getValue().uncompressedSize + " bytes)");
                }
            }
        }
        System.out.println("done");
    }
}
