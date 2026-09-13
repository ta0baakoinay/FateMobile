import java.nio.file.Files;
import java.nio.file.Paths;

public final class TexLookup {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(args[0])); // prontera.gnd
        GndFile gnd = GndFile.parse(data);

        try (GrfArchive dataGrf = GrfArchive.open(args[1]); GrfArchive fateGrf = GrfArchive.open(args[2])) {
            int found = 0, missing = 0;
            for (String tex : gnd.textures) {
                String path = "data\\texture\\" + tex;
                boolean inFate = fateGrf.contains(path);
                boolean inData = dataGrf.contains(path);
                System.out.println((inFate || inData ? "FOUND  " : "MISSING") + " fate=" + inFate + " data=" + inData + "  len=" + tex.length() + " bytes=" + bytesHex(tex));
                if (inFate || inData) found++; else missing++;
            }
            System.out.println("found=" + found + " missing=" + missing);
        }
    }

    private static String bytesHex(String s) {
        byte[] b = s.getBytes(java.nio.charset.Charset.forName("EUC-KR"));
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString();
    }
}
