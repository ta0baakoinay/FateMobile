import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal CLI for GrfArchive — used to verify the reader against real GRF
 * files and to pull individual assets out during pipeline development.
 * Not part of the shipped app; a developer/operator tool only.
 *
 * Usage:
 *   java GrfTool list <path-to.grf> [namePrefix] [maxResults]
 *   java GrfTool extract <path-to.grf> <entryNameInGrf> <outputFile>
 *   java GrfTool stat <path-to.grf>
 */
public final class GrfTool {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: list|extract|stat <grf> ...");
            System.exit(1);
        }
        String cmd = args[0];
        String grfPath = args[1];

        try (GrfArchive grf = GrfArchive.open(grfPath)) {
            switch (cmd) {
                case "stat": {
                    System.out.println("Entries: " + grf.entries().size());
                    long files = grf.entries().values().stream().filter(GrfArchive.Entry::isFile).count();
                    System.out.println("Files (non-directory): " + files);
                    break;
                }
                case "list": {
                    String prefix = args.length > 2 ? args[2].toLowerCase() : "";
                    int max = args.length > 3 ? Integer.parseInt(args[3]) : 50;
                    int shown = 0;
                    List<String> names = new ArrayList<>();
                    for (Map.Entry<String, GrfArchive.Entry> e : grf.entries().entrySet()) {
                        if (!e.getValue().isFile()) continue;
                        if (!prefix.isEmpty() && !e.getKey().contains(prefix)) continue;
                        names.add(e.getValue().name + "  (" + e.getValue().uncompressedSize + " bytes)");
                        shown++;
                        if (shown >= max) break;
                    }
                    names.forEach(System.out::println);
                    System.out.println("--- shown " + shown + " ---");
                    break;
                }
                case "extract": {
                    String entryName = args[2];
                    String outPath = args[3];
                    byte[] data = grf.extract(entryName);
                    try (FileOutputStream fos = new FileOutputStream(outPath)) {
                        fos.write(data);
                    }
                    System.out.println("Wrote " + data.length + " bytes to " + outPath);
                    break;
                }
                default:
                    System.err.println("Unknown command: " + cmd);
                    System.exit(1);
            }
        }
    }
}
