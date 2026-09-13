# tools

## grf/

A real GRF archive reader, GAT/GND (walkability/ground-mesh) parser, SPR sprite
decoder, and top-down map rasterizer — plain Java (JDK 17+, no external
dependencies, `java.util.zip.Inflater` handles the zlib decompression). See
[`../docs/FATE_MMO_MOBILE_ASSETS.md`](../docs/FATE_MMO_MOBILE_ASSETS.md) for
the full format documentation, all verified against the operator's real Fate
MMO client files rather than assumed from memory.

Build:
```
cd tools/grf
javac -d out -encoding UTF-8 -sourcepath src src/GrfTool.java src/MapRasterizer.java src/SprTest.java
```

Usage examples (see each tool's own `--help`-equivalent usage string):
```
java -cp out GrfTool list "F:\FateMMO\data.grf" prontera 20
java -cp out GrfTool extract "F:\FateMMO\data.grf" "data\prontera.gat" out\prontera.gat
java -cp out MapRasterizer out\prontera.gnd "F:\FateMMO\data.grf" "F:\FateMMO\Fate.grf" out\prontera_map.png 16
```

No copyrighted Ragnarok/Fate MMO assets (GRF files, extracted sprites/textures,
rasterized maps) are bundled in this repository — these tools are run against
the server operator's own legally-held client files, locally, with output
landing in `out/` (gitignored) or copied into `android/app/src/main/assets/`
for the specific maps/sprites the app actually bundles.

**Excluded from every pipeline run, per the operator**: `hd.grf` (PC-only
high-resolution assets, not appropriate for mobile) and `graymap.grf` (not
even part of the client's own load order per `DATA.ini`).

**Status**: GRF/GAT/GND/SPR readers done and verified; ACT (animation) parsing
started but not completed to the same confidence level — see
`FATE_MMO_MOBILE_ASSETS.md` §6. No RSW (3D prop placement) support yet.
