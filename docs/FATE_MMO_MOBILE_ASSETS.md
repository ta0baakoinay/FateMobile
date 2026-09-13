# Fate MMO Mobile — Asset Pipeline (GRF/GND/GAT/SPR formats)

Companion to [`FATE_MMO_MOBILE_ARCHITECTURE.md`](FATE_MMO_MOBILE_ARCHITECTURE.md) §7 (asset pipeline) and [`FATE_MMO_MOBILE_PROTOCOL.md`](FATE_MMO_MOBILE_PROTOCOL.md) (server wire protocol — unrelated to this document, which covers only the **client-side file formats**: how Ragnarok's GRF archives, ground meshes, walkability grids, and sprites are structured).

**Source of truth**: the operator's real Fate MMO client at `F:\FateMMO` — `Fate.grf`, `palettes.grf`, `data.grf` (per `DATA.ini`'s load order). `hd.grf` and `graymap.grf` are explicitly excluded from this pipeline per the operator (`hd.grf` is PC-only high-res assets; `graymap.grf` isn't even in `DATA.ini`'s load order). These are public, well-documented Ragnarok Online client file formats — independent of any private server code, and not derived from or requiring the FateRO server source. Every struct layout below was **verified against the real files** (byte-for-byte arithmetic matching a file's actual size, or a successfully-decoded real texture/map), not implemented from memory alone and left unchecked.

## 1. GRF archive format (version 0x200)

Tooling: [`tools/grf/src/GrfArchive.java`](../tools/grf/src/GrfArchive.java), CLI in [`GrfTool.java`](../tools/grf/src/GrfTool.java).

```text
offset  size  field
0       16    signature = "Master of Magic\0"
16      14    reserved (fixed 00,01,02...0D pattern in every real GRF seen)
30      4     fileTableOffset (uint32 LE) — add 46 (header size) for the absolute file-table position
34      4     count1 (uint32 LE)
38      4     count2 (uint32 LE)  — realFileCount = count2 - count1 - 7
42      4     version (uint32 LE) — 0x200 for all three files here; other versions not implemented
                total header = 46 bytes
```
Verified against `Fate.grf`: header declared `count2=135473, count1=0` → expected 135,466 files; the parser found **exactly** 135,466 table entries with zero leftover bytes. `fileTableOffset` (`0x651C6D80`) plus header size lands within a few bytes of end-of-file, consistent with a compressed table living there.

**File table** (at `46 + fileTableOffset`): `uint32 compressedSize; uint32 uncompressedSize;` followed by `compressedSize` bytes of a zlib stream (`java.util.zip.Inflater`, no external dependency) that inflates to `uncompressedSize` bytes — the actual table. Each table entry: `NUL`-terminated filename (**EUC-KR** encoded — Korean folder/file names are the norm, e.g. `맵\prt_바닥01.bmp`), then 17 fixed bytes: `uint32 compressedSize; uint32 compressedSizeAligned; uint32 uncompressedSize; uint8 flags; uint32 dataOffsetRelative` (add 46 for the absolute offset). `flags & 0x01` = "is a file" (vs. a directory placeholder); `flags & 0x02` or `0x04` = DES-encrypted — **not implemented** (not needed for any map/texture file touched so far; `GrfArchive.extract()` throws rather than silently producing corrupt output if it ever hits one).

Validated end-to-end by extracting `data\clientinfo.xml` from `Fate.grf` and getting back valid, readable XML (see §4 for what that revealed about the real server address/client version).

## 2. GAT walkability format (version 1.2)

Tooling: [`GatFile.java`](../tools/grf/src/GatFile.java).

```text
offset  size  field
0       4     signature "GRAT"
4       1     version major
5       1     version minor
6       4     width (int32 LE)
10      4     height (int32 LE)
14      ...   width*height cell records, 20 bytes each:
                float bottomLeftHeight, bottomRightHeight, topLeftHeight, topRightHeight
                int32 type  (0 = walkable ground, 3 = walkable water; other values = not walkable)
```
Verified against `prontera.gat`: `14 + 312*392*20 = 2,446,094` bytes — **exactly** the real file's size. Cell-type histogram is a plausible ~50/50 walkable/non-walkable split for a town map (61,476 walkable vs. 60,828 non-walkable out of 122,304 cells); the center cell (a town-plaza tile) is flat (`height=1.0` on all four corners) and walkable, as expected.

## 3. GND ground-mesh format (version 1.7)

Tooling: [`GndFile.java`](../tools/grf/src/GndFile.java). This is the format that actually lets a top-down renderer draw real ground textures instead of flat colors.

```text
offset  size  field
0       4     signature "GRGN"
4       1     version major
5       1     version minor
6       4     width (int32 LE)          — half of the companion .gat's width (156 vs 312 for prontera — confirmed)
10      4     height (int32 LE)         — half of .gat's height (196 vs 392 — confirmed)
14      4     zoom (float LE)           — 10.0 for prontera; world units per cube
18      4     textureCount (int32 LE)
22      4     textureNameLength (int32 LE) — 80 for this version
26      ...   textureCount * textureNameLength bytes: NUL-padded EUC-KR texture names,
              relative to data\texture\
...     4     lightmapCount (int32 LE)
...     4     lightmap cellPerCellX (int32 LE) — 8
...     4     lightmap cellPerCellY (int32 LE) — 8
...     4     lightmap "gridSizeCell" field (int32 LE) — present in the format but its
              real meaning didn't matter for this pipeline: the ACTUAL per-entry byte
              size (confirmed by exact file-size arithmetic, not this field's value of
              1) is cellPerCellX * cellPerCellY * 4 = 256 bytes. Skipped entirely —
              lightmaps are cosmetic per-pixel shading, not needed for texture identity.
...     4     surfaceCount (int32 LE)
...     ...   surfaceCount * 40-byte surface records:
                float u[4], v[4]        — texture-space UV corners of this face
                int16 textureIndex      — into the texture name table above
                int16 lightmapIndex     — unused by this pipeline
                uint8 color[4]          — unused by this pipeline
...     ...   width*height * 28-byte cube records (row-major):
                float height[4]         — 4 corner heights (unused by the flat 2D rasterizer)
                int32 tileUp            — surface index for the top face, or -1 if none
                int32 tileSide          — side face surface index (unused — no 3D walls rendered)
                int32 tileFront         — front face surface index (unused)
```
**Every one of these struct sizes (256/40/28 bytes) was confirmed, not assumed**: given the real `prontera.gnd`'s total size (3,335,430 bytes) and the known, directly-read `surfaceCount` (28,511), the equation `986 (header+textures) + 16 (lightmap header) + 5226×256 (lightmap data) + 4 (surfaceCount field) + 28511×40 (surfaces) + 30576×28 (cubes) = 3,335,430` holds **exactly**. That's what justifies the 40-byte surface / 28-byte cube sizes above rather than a plausible-but-wrong alternative.

**UV coordinates are sub-regions of a texture atlas, not 0–1 full-texture spans** — e.g. prontera's central plaza cube samples `u=[0.5,0.75], v=[0.5,0.75]` of a 256×256 cobblestone texture (`data\texture\맵\prt_바닥01.bmp`, confirmed to decode as a real, recognizable radial-cobblestone plaza texture). [`MapRasterizer.java`](../tools/grf/src/MapRasterizer.java) crops the **axis-aligned bounding box** of each surface's UV quad — a deliberate simplification that ignores any rotation baked into vertex winding order, so a minority of tiles may render mirrored/rotated relative to the real 3D client. That's a documented trade-off for a first working 2D top-down renderer, not a silent bug.

**Rasterization is a one-time, offline step** (not done on-device): `MapRasterizer` composites every cube's cropped, UV-sampled top-face texture into one flat top-down PNG per map (prontera: 3744×4704 px at a 24px/cube tile size, ~9.7MB as PNG — a JPEG re-encode is the intended shipped format, since ground art has no transparency and tolerates lossy compression well). The Android client displays this as a large pannable bitmap rather than re-rasterizing GND geometry live — this is a deliberate scope decision (no 3D geometry, no walls/props from the companion `.rsw`, no per-frame GPU rendering of the mesh) appropriate for a 2D top-down mobile client, not a limitation of the underlying data.

**Verification**: the rasterized prontera output is immediately recognizable as the real map — the cross-shaped road layout, central diamond plaza, octagonal ring road, and the curved moat at the south edge all match the known real Prontera layout. This was visually confirmed, not just numerically self-consistent.

## 4. What extracting `clientinfo.xml` revealed, and how the address question was actually settled

`data\clientinfo.xml` inside `Fate.grf` (highest load-order priority per `DATA.ini`) lists:
- `<display>Singapore 1</display>`, `<address>167.104.101.102</address>`, `<port>6900</port>`, `<version>55</version>`, `<langtype>1</langtype>`.
- A second `<connection>`, `<display>Test Server</display>`, `<address>127.0.0.1</address>` — matches what the operator described separately as a local/test address.

This initially superseded an earlier, verbally-provided production IP (`51.79.147.208`) in `server_config_production.json`, on the reasoning that a live client config file is stronger evidence than a verbally-recalled address. That reasoning turned out to be backwards in this case: the operator subsequently provided direct SSH access to `51.79.147.208`, which confirmed it's running the actual `FateRO` checkout (`/home/debian/FateRO`, git remote `ta0baakoinay/FateRO`, `login_port: 6900` in `conf/login_athena.conf`) — i.e., direct access to the running server is stronger evidence than a bundled config snapshot that could predate a server move. `server_config_production.json` now points at `51.79.147.208`; `clientVersion: 55` from `clientinfo.xml` is kept (the login handler doesn't enforce it either way, see protocol doc §3.1), but is itself still just a client-side value, not something re-derived from the server. The lesson generalizes: when a file-based snapshot and a live, independently-verifiable source disagree, prefer whichever was actually checked against the thing in question, not whichever was extracted more recently.

## 5. SPR sprite format (version 2.1 confirmed)

Tooling: [`SprFile.java`](../tools/grf/src/SprFile.java) (pipeline/offline) and its Kotlin port for the app itself is not yet written — only the offline decode has been verified so far; see §7.

```text
offset  size  field
0       2     signature "SP"
2       1     version minor
3       1     version major        — only 2.1 confirmed/implemented; other versions rejected
4       2     numPalImages (uint16 LE)  — indexed (8-bit palette) frame count
6       2     numRgbaImages (uint16 LE) — true-color frame count; only 0 verified (this
                                          sprite has none) — SprFile.java refuses nonzero
                                          rather than guess that path's layout
8       ...   numPalImages indexed frames, each:
                uint16 width, uint16 height, then RLE-encoded pixel data (see below)
...     1024  palette: 256 * (r,g,b,a) bytes — present iff numPalImages > 0
```

**Indexed frame pixel encoding is RLE, not raw bytes** — discovered by trial: assuming raw `width*height` bytes per frame produced a buffer underflow on frame 1, because frame 0 wasn't actually that many bytes. Hand-decoding the real byte stream against the frame's known 38×74 dimensions confirmed the scheme: a `0x00` byte is followed by a count byte meaning "that many transparent (palette index 0) pixels"; any nonzero byte is one literal palette-index pixel. Decoding all 110 real frames of the novice body sprite this way lands **exactly** on a 1024-byte palette at EOF with zero leftover/shortfall bytes — strong end-to-end confirmation across the whole file, not just the first frame.

**Verification**: frame 0, rendered through the extracted palette (index 0 = transparent, the standard convention), is immediately recognizable as the classic Ragnarok Online male Novice sprite. Extracted from `data\sprite\인간족\몸통\남\초보자_남.spr` (human race → body → male → novice) in `data.grf`.

## 6. ACT animation format — opcode/header confirmed, full layer parsing not completed

`data\sprite\인간족\몸통\남\초보자_남.act` was inspected (signature "AC", version 2.5, `numActions=104` as a `uint16` at offset 4 — a plausible value: 13 action groups × 8 directions matches the well-known convention that actions 0–7 are the idle animation's 8 facing directions and 8–15 are the walk cycle's, though that grouping itself wasn't independently re-derived from this file). Byte-exact parsing of the per-frame layer struct (`offsetX/Y`, `spriteFrameIndex`, `mirror`, `color`, `scale`, `rotation`, `spriteType`, and version-gated `width`/`height` fields) was **not** completed to the same confidence level as GRF/GAT/GND/SPR — early field reads produced implausible values (e.g. an apparent `numLayers=0` on the very first frame, an eventId that didn't look like the expected `-1` sentinel) that weren't run to ground with the same file-size arithmetic technique used elsewhere in this document, for time reasons. **Consequence**: the Android client currently renders character sprites as a single static frame (frame 0, the idle-facing-forward pose) rather than a direction/action-driven animation. This is an honest, documented limitation, not a silent gap — see `FATE_MMO_MOBILE_ROADMAP.md` for where full ACT parsing is picked back up.

## 7. What's not implemented yet

- **ACT full layer parsing** (§6) — static frame 0 only, no directional/walk animation yet.
- **RSW** (world file: 3D model/prop placement, lighting, water plane) — ground-only rendering skips all of this; buildings, trees, and other static props from the real map are absent from the rasterized output.
- **DES-encrypted GRF entries** — `GrfArchive.extract()` detects and refuses these rather than producing silently-corrupt output; not needed for any file this pipeline has touched so far.
- **Kotlin ports live in `android/app/.../world/`**: only `GatFile.kt` (walkability) has been ported so far, verified against the same real `prontera.gat` bytes as the Java tooling version. `GndFile`/`SprFile`/the rasterizer stay as offline `tools/grf/` pipeline steps — the Android app consumes their *output* (a JPEG + the raw `.gat`), not the GND/SPR parsers themselves, since ground/sprite conversion is meant to happen once per map/sprite, not on-device.
