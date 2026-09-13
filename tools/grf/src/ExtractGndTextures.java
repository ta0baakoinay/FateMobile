import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Extracts every texture a .gnd file references, avoiding shell/argv encoding issues with EUC-KR paths. */
public final class ExtractGndTextures {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(args[0])); // .gnd path
        String dataGrfPath = args[1];
        String fateGrfPath = args[2];
        String outDir = args[3];

        GndFile gnd = GndFile.parse(data);
        try (GrfArchive dataGrf = GrfArchive.open(dataGrfPath); GrfArchive fateGrf = GrfArchive.open(fateGrfPath)) {
            for (int i = 0; i < gnd.textures.length; i++) {
                String tex = gnd.textures[i];
                String path = "data\\texture\\" + tex;
                byte[] bytes;
                if (fateGrf.contains(path)) {
                    bytes = fateGrf.extract(path);
                } else if (dataGrf.contains(path)) {
                    bytes = dataGrf.extract(path);
                } else {
                    System.err.println("Missing: " + path);
                    continue;
                }
                String outName = outDir + "/tex_" + i + ".bmp";
                try (FileOutputStream fos = new FileOutputStream(outName)) {
                    fos.write(bytes);
                }
                System.out.println("Wrote " + outName + " (" + bytes.length + " bytes)");
            }
        }
    }
}
