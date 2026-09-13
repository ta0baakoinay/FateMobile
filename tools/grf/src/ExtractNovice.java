import java.io.FileOutputStream;

public final class ExtractNovice {
    public static void main(String[] args) throws Exception {
        try (GrfArchive grf = GrfArchive.open(args[0])) {
            String[] names = {
                "data\\sprite\\인간족\\몸통\\남\\초보자_남.spr",
                "data\\sprite\\인간족\\몸통\\남\\초보자_남.act",
                "data\\sprite\\인간족\\몸통\\여\\초보자_여.spr",
                "data\\sprite\\인간족\\몸통\\여\\초보자_여.act"
            };
            String[] outs = {"out/novice_m.spr", "out/novice_m.act", "out/novice_f.spr", "out/novice_f.act"};
            for (int i = 0; i < names.length; i++) {
                byte[] data = grf.extract(names[i]);
                try (FileOutputStream fos = new FileOutputStream(outs[i])) {
                    fos.write(data);
                }
                System.out.println("Wrote " + outs[i] + " (" + data.length + " bytes)");
            }
        }
    }
}
