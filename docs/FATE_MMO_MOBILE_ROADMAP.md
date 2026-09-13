# Fate MMO Mobile — Development Roadmap

Build order is strictly incremental, per the project brief §34: each phase must work end-to-end against the real FateRO server before the next phase starts. No phase's UI/networking is faked or stubbed with fabricated data — if a system isn't implemented yet, it's absent or clearly marked `TODO`/`NOT IMPLEMENTED`, never a fake placeholder that looks functional.

## Phase 0 — Foundations (this delivery)

* [x] Inspect `FateRO` source, confirm it's stock rAthena (PACKETVER 20250716), no packet-level customization.
* [x] `FATE_MMO_MOBILE_PROTOCOL.md` — login protocol fully byte-mapped from source; char/map entry points confirmed, deeper packets deferred to their phases.
* [x] `FATE_MMO_MOBILE_ARCHITECTURE.md`.
* [x] This roadmap.
* [x] Buildable Android Studio project skeleton (Gradle + NDK/CMake wired, even before native code does anything real).
* [x] Phase 1 network PoC (below), implemented and buildable.

## Phase 1 — Network Proof of Concept ✅ (delivered alongside this roadmap)

Goal: prove the login handshake against the real server, nothing else.

* Native Android login screen (Kotlin Views, no WebView) — username/password fields, server host:port from `server_config.json`, Login/Settings/Exit buttons.
* `LoginClient.kt`: opens a TCP socket to the configured login server, sends `CA_LOGIN` (0x0064) exactly per the protocol doc, parses `AC_ACCEPT_LOGIN` (0xAC4) or `AC_REFUSE_LOGIN` (0x083E).
* Visible connection-state machine on screen: `Connecting… / Authenticating… / Success (shows AID + char-server list) / Failed (shows server's error code) / Disconnected`.
* Runs on the network thread; never blocks the UI thread (Kotlin coroutine + `Dispatchers.IO`).
* **Explicitly out of scope for Phase 1:** character list, char-server/map-server connection, any rendering. Selecting "Success" only proves the handshake — it does not proceed further yet. That transition is Phase 2/3 work and must not be faked with placeholder character data.

Exit criteria: a real Fate MMO account can log in from an Android device/emulator and the app correctly displays either the account's char-server list or the server's actual refusal reason — both observed against the live server, not mocked.

## Phase 2 — Character Selection ✅ (protocol + client implemented)

Depended on byte-mapping the char-server auth handshake and the char-list auto-push, which was `TODO` after Phase 1 — now fully done in protocol doc §4, including two version-gated details a generic rAthena guide would get wrong for this exact server build: `HC_ACCEPT_MAKECHAR` is `0x0B6F` (not the classic `0x006D`), and character deletion needs the account's **birthdate**, not an email, per this server's shipped `char_del_option: 2`.

* [x] Byte-map the char-list reply — turned out to be a 4-packet auto-push (`0x082D` slot summary → `0x006B` character array → `0x09A0` page notify → `0x020D` block/ban list), not the `0x099D` pagination packet the Phase 1 doc guessed might be relevant (that one's only used for later re-pagination, not the initial push — corrected in protocol doc §4.2).
* [x] `CharServerClient.kt`: persistent-socket char-server client — `CH_ENTER` (0x0065) using `login_id1/login_id2/AID/sex` from Phase 1, drains the full char-list push, `CH_SELECT_CHAR`, `CH_MAKE_CHAR`, `CH_DELETE_CHAR`.
* [x] Character list screen (`CharSelectActivity.kt`): name, job id, base level, job level; Play/Create/Delete/Back. **No sprite yet** — that needs the asset pipeline/renderer (§7/§9 of the architecture doc), which doesn't exist until Phase 3+; showing one now would mean faking it, so the row is text-only until there's a real sprite to draw.
* [x] Character create (`CH_MAKE_CHAR`, opcode `0x0A39` for this client version) — name only in the UI; hair/job customization skipped since the server hardcodes starting stats regardless (protocol doc §4.5) and a fuller picker is cosmetic polish, not needed to prove the flow.
* [x] Character delete (`CH_DELETE_CHAR`, opcode `0x0068`) — UI asks for the account **birthdate (YYMMDD)**, matching this server's actual shipped config rather than a generic "email" assumption a non-verified implementation would guess.
* Selecting a character shows the server's real map-redirect response (map name/ip/port) but does **not** open a map connection — that's Phase 3's job, and faking a map transition here would violate the no-fake-features rule.
* PIN code entry (`HC_ACK_PINCODE`/`0x08B9`) is recognized (won't desync the stream) but has no UI — `pincode_enabled: no` in this server's shipped conf, so building that screen now would be implementing a system not actually in use, per the incremental-build rule. Revisit if the live server enables it.

**Live-verified against the real production server on 2026-09-14** (direct SSH access to the VPS, real test accounts, packet-level test harness reusing the exact same byte layouts as the shipped Kotlin code) — and it caught a real bug the source-only pass missed: `CH_ENTER`'s packet-size constant was arithmetically wrong (`2+4+4+4+1` was miscounted as 17; it's actually 15, and the real struct has a 2-byte gap before `sex` that was never filled in). The result was `sex` silently corrupting to 0 on every attempt, causing an opaque, 100%-reproducible char-server rejection that took a live packet capture (not source reading) to actually diagnose — see protocol doc §4.1. **Fixed and re-verified**: login → char-list → character select → map-server redirect now completes successfully end-to-end against the live server, with a real existing character (`mobiletest`, created via the actual PC client to rule out the bug being server-side). Also found live (not in the original source trace) and now handled: the server sends an unsolicited `HC_ACK_PINCODE` (`0x08B9`) before the real response to select/create/delete even with `pincode_enabled: no` — see protocol doc §4.2.

Exit criteria: **met and live-verified** — an existing Fate MMO character (created on PC) is visible and selectable from the Android client's actual networking code.

**Separately found live, not a client bug**: character *creation* is currently blocked on this server — `char_new: yes` is in the on-disk config, but the running char-server process rejects every creation attempt the same way it would if that were `no`, strongly suggesting the process has a stale in-memory config value from before that setting was last changed (rAthena reads config once at startup, not on a timer) and needs a restart to pick up the current file. Not re-tested after a restart as of this writing.

## Phase 3 — Map Connection 🟡 (handshake + real rendering + local movement done; server-synced movement not started)

The `packet_db[0x0072]` question flagged after Phase 2 turned out to matter a lot: `0x0072` is **not** `CZ_ENTER` for this build at all — the server reassigns it to `clif_parse_UseSkillToId` for modern clients. Tracing the actual runtime table (`clif_packetdb.hpp` + `clif_shuffle.hpp`) turned up two more things a non-verified implementation would have gotten wrong or badly overbuilt: the map server has both a packet-ID shuffle *and* an XOR obfuscation mechanism in its source, both of which happen to resolve to a no-op for PACKETVER 20250716 (confirmed by tracing the actual zero-valued constants, not assumed from the `#ifdef` alone). Full derivation in protocol doc §5.

### 3a — Protocol handshake ✅

* [x] Resolve the real `CZ_ENTER` opcode/layout for this PACKETVER: `0x0436`, 23 bytes (protocol doc §5.1) — not `0x0072`, and not the 19-byte legacy form either.
* [x] `MapServerClient.kt`: `CZ_ENTER` handshake using the char-select redirect data from Phase 2, reads the session-id echo (`0x0283`), waits out the invisible char-server round trip (protocol doc §5.3), and parses `ZC_ACCEPT_ENTER` (`0x02EB`) — including reverse-engineering the packed 3-byte X/Y/direction encoding (`WBUFPOS`, protocol doc §5.5) to get real coordinates — or `ZC_REFUSE_ENTER`/`SC_NOTIFY_BAN` on failure.
* [x] Resolved (but not yet sent) `CZ_NOTIFY_ACTORINIT` (LoadEndAck, `0x007D`) and `CZ_REQUEST_MOVE` (WalkToXY, `0x035F`) opcodes, plus the server's own-movement confirmation `ZC_NOTIFY_PLAYERMOVE` (`0x0087`) — protocol doc §5.6.

### 3b — Real asset pipeline + local rendering ✅ (new — went well beyond the original "flat placeholder tiles" plan, per explicit request for real assets)

The operator provided the actual Fate MMO client (`F:\FateMMO`, `Fate.grf`/`palettes.grf`/`data.grf` — `hd.grf` and `graymap.grf` excluded per operator instruction). Built and verified (against these real files, not memory) a full offline asset pipeline — see `FATE_MMO_MOBILE_ASSETS.md`:

* [x] **GRF archive reader** (`tools/grf/`) — version 0x200, verified by exact file-table arithmetic against `Fate.grf` (135,466 files, zero discrepancy) and by extracting real, readable `clientinfo.xml`.
* [x] **GAT walkability parser** — verified by exact file-size arithmetic against real `prontera.gat` (312×392 cells).
* [x] **GND ground-mesh parser** — every struct size (lightmap/surface/cube) confirmed by exact file-size arithmetic against real `prontera.gnd`, not assumed.
* [x] **Map rasterizer** — composites the real ground textures (found and extracted from `data.grf`, e.g. the actual Prontera plaza cobblestone texture) into one top-down image. Visually confirmed as recognizably real Prontera (cross-road layout, central plaza, moat).
* [x] **SPR sprite parser** — version 2.1, including reverse-engineering the RLE pixel encoding (not documented anywhere available, worked out by hand against the real byte stream and confirmed by decoding all 110 real frames of the novice sprite cleanly). Rendered frame 0 is visually confirmed as the real, recognizable RO Novice sprite.
* [x] **Android integration**: `GameMapView.kt` (Canvas-based, real ground image + real character sprite + real GAT wall-collision), bundled for prontera only (`android/app/src/main/assets/maps/prontera/`), wired into `MapActivity` — shown when the char-select redirect's map name is prontera, with the character placed at the real server-confirmed spawn coordinates from `ZC_ACCEPT_ENTER`.
* [ ] **Not done**: ACT animation layer parsing (directional/walk sprite animation) — character renders as a single static idle frame; see `FATE_MMO_MOBILE_ASSETS.md` §6 for exactly where that parsing attempt stalled.
* [ ] **Not done**: RSW props (buildings/trees/models) — ground texture only, no 3D geometry.
* [x] **Done (2026-09-14)**: the two-tier "download what's needed now" / "download all" asset delivery the operator asked for. See §3d below.
* [ ] **Not done**: maps other than prontera — each additional map needs its own pipeline run (`tools/grf` against that map's `.gnd`/`.gat`) and its own bundled/downloaded asset pack.

### 3c — Movement: real locally, not yet server-synced 🟡

* [x] Virtual joystick (`GameMapView.kt`) driving **client-local** movement, constrained by the **real** GAT walkability grid (walls actually block movement, using real map collision data).
* [ ] **Not done, deliberately**: sending `CZ_NOTIFY_ACTORINIT`/`CZ_REQUEST_MOVE` to the server. Reason: `LoadEndAck` is what triggers the server to start streaming the *entire rest* of the gameplay protocol (inventory, stats, every nearby entity's spawn packet) on this socket, and — unlike every packet verified so far — there is no central length registry for server→client packets to safely skip the ones this client doesn't handle yet (the `packet_db` table only covers packets the server *parses from the client*). Opening that floodgate without a way to safely discard unrecognized packets risks silently corrupting the read stream. This needs its own dedicated reverse-engineering pass (enumerating and length-mapping the server→client packet surface), not an extension of the CZ_ENTER-style verification already done.

### 3d — Two-tier asset download (operator's explicit request) — built and live-verified (2026-09-14)

The operator asked for two login-area choices: "download what's needed now" (then fetch more on demand) vs. "download all". Built using real infrastructure, not a mock: the existing TCP protection proxy (`167.104.101.102`) got a fourth port forward (`8080 -> backend:80`), the backend's Apache now serves `/var/www/html/fatemobile-assets/` (manifest + the three converted prontera/novice files) with port 80 firewalled at `iptables` to accept only the proxy's IP — the raw backend is still unreachable directly, preserving the whole point of fronting it with a DDoS-protection proxy. See `FATE_MMO_MOBILE_ASSETS.md` §8 for the full writeup (server topology, hash-verification design, what's still missing like resumable downloads).
* `AssetManifest.kt` / `AssetDownloadManager.kt` (`android/app/.../assets/`) — fetch `manifest.json`, download with SHA-256 verification-while-streaming, store under `filesDir/assets/` (never in the bundled APK `assets/`).
* Login screen has a "DOWNLOAD ASSETS" button offering the essential/full choice with live progress; `MapActivity` prefers a downloaded copy over the bundled one when present, so the client works with or without ever using the downloader.
* **Honest limitation carried forward**: both packs currently contain the same three files, since prontera + the novice sprite are still the only converted assets (§3b). The two-tier *plumbing* is real and live-tested end-to-end; it just doesn't have more than one map's worth of content to differentiate on yet. Adding a new map/sprite now means: run `tools/grf` → upload the output + updated `manifest.json` to the backend's `fatemobile-assets/` dir (through the proxy or via SSH) → no APK rebuild needed for that part.

Exit criteria: character enters a real map at the correct server-assigned coordinates and can walk around it against real wall collision, rendered with real Fate MMO art — **met, with server-synced movement and other maps/entities explicitly still open**.

**3a is now live-verified end-to-end (2026-09-14)**, immediately following the Phase 2 bug fix above (the same corrupted-`sex` bug had been blocking map-server entry too, since it never got past char-select): `CZ_ENTER` → session echo → real `ZC_ACCEPT_ENTER` with correctly-decoded spawn coordinates (`x=51, y=108`), against the live server, using the real character `mobiletest`. One more live-only finding along the way: an undocumented `ZC_EXTEND_BODYITEM_SIZE` (`0x0B18`) packet precedes `ZC_ACCEPT_ENTER` — missed by the original source trace, now handled — see protocol doc §5.4. The account's actual starting map turned out to be a custom `new_3-1.gat`, not `prontera` — **3b's rendering (real map/sprite/local movement) is still prontera-only**, so a real character on this server won't see rendered terrain yet on its actual spawn map; that's a straightforward extension (run the same `tools/grf` pipeline against `new_3-1`), not a fix, once prioritized.

**Still not yet run on a real Android device** — everything above was verified via a standalone JVM test harness reusing the exact packet-building code paths, not the compiled APK itself. Installing and running the actual app against this live server is the next natural step.

## Phase 4 — Movement

Depends on closing Phase 3b first: sending `CZ_NOTIFY_ACTORINIT`, verifying its actual opcode for this PACKETVER the same way `CZ_ENTER`'s was (protocol doc §5.6 explicitly flags it as unverified, not assumed safe just because it's next in line), and having at least a placeholder renderer to put a character sprite on.

* [ ] `CZ_NOTIFY_ACTORINIT` ("LoadEndAck") — byte-map its real opcode/shuffle status for PACKETVER 20250716 before sending it, same rigor as §5.1.
* [ ] Virtual joystick input.
* [ ] Movement intent packets sent to server; rendered position always derived from server-confirmed state (interpolated, never invented — see architecture doc §4).
* [ ] Other players', NPCs', and monsters' positions rendered from `ZC_NOTIFY_STANDENTRY`-family packets (to be byte-mapped from `clif.cpp` at this phase — expect the same shuffle/version-gating surprises §5 turned up for `CZ_ENTER`, don't assume a generic guide's opcode is right for this build).
* [ ] Map-to-map transitions (warps) trigger a fresh `CZ_ENTER` (`0x0436`) handshake to the new map.

Exit criteria: a mobile player and a PC player can see each other move on the same map in real time.

## Phase 5 — Basic Gameplay

* [ ] Tap-to-target enemies; dedicated attack button sends the server's basic-attack intent packet.
* [ ] Item pickup (tap loot, or server-supported auto-loot if FateRO's config enables it — verify, don't assume).
* [ ] HP/SP bars driven by server packets only.
* [ ] Death/respawn flow matching server behavior (no client-invented respawn timers).
* [ ] Map change handling (warp portals, teleport skills/items).

Exit criteria: a character can fight a monster, die, respawn, and pick up a dropped item, with the server as sole authority for every outcome — cross-checked against the same actions on PC to confirm parity.

## Phase 6 — UI

* [ ] Inventory (grid, stacks, equip tab) — reads server-authoritative inventory packets only.
* [ ] Equipment screen matching FateRO's actual equip-slot set (verify slot list from `mmo.hpp`/item DB rather than assuming the generic RO slot list, in case Fate MMO has custom slots).
* [ ] Skill hotbar with cooldown/SP-cost display sourced from server data.
* [ ] Mobile chat (public/party/guild/whisper/system/NPC channels).
* [ ] Party panel, quest tracker, minimap, character stat screen.

Exit criteria: full HUD parity of *information* (not layout) with what the PC client shows, verified field-by-field against a live character.

## Phase 7 — Advanced Systems

Only build what FateRO actually runs (per brief §34's "only implement systems actually used by Fate MMO" — check `conf/battle_athena.conf` / enabled features before investing in, e.g., WoE or homunculus support if this server disables them):

* [ ] Guild (members, chat, skills, storage if enabled).
* [ ] Storage, trading.
* [ ] Pets, homunculus (if enabled server-side).
* [ ] PvP, WoE (if enabled server-side).
* [ ] Remaining skill effects/animations.

## Cross-cutting, ongoing throughout every phase

* Debug overlay (§37 of brief): FPS, ping, server, map, coordinates, entity count, memory, packet log, connection state — build this in Phase 1 and keep extending it; it's the primary tool for verifying "not faked" at every later phase.
* Logging (§38): login/auth, packet errors, disconnects, map/asset loading, rendering errors, crashes, Android lifecycle — structured from Phase 1 onward, not bolted on later.
* Performance budget (§26): re-check on a genuine low/mid-range device at the end of every phase, not just at the end of the project.
* Any packet this roadmap references as "TODO — byte-map at this phase" must actually be re-verified against `FateRO` source at that time — server code can change between now and then.
