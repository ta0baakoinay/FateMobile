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

Exit criteria: an existing Fate MMO character (created on PC) is visible and selectable from the Android client, and a new character created on Android is visible on the PC client's char-select screen. **Not yet verified against a live server by a human** — the parsing is byte-accurate to the source, but nobody has run this against a real FateRO instance yet; do that before calling Phase 2 fully closed.

## Phase 3 — Map Connection

Depends on: resolving the actual `packet_db[0x0072]` field offsets at runtime for PACKETVER 20250716 (protocol doc §5.1 flags this as dynamically resolved, not a fixed struct — must be confirmed before coding, not assumed from the "default form" comment in source).

* [ ] Migrate networking from the Phase 1 Kotlin PoC into the `native/networking` + `native/protocol` C++ layer (per architecture doc §2, this is the deliberate migration point).
* [ ] `CZ_ENTER` (0x0072) handshake to the map server using the char-select redirect data (0xAC5 packet from Phase 2).
* [ ] Minimal map load: walkable-cell grid, ground rendering (even flat-color placeholder tiles are fine here — geometry correctness matters, art doesn't yet).
* [ ] Character spawns at the server-reported position; camera follows.

Exit criteria: character enters a real map (e.g. `prontera`) at the correct server-assigned coordinates, visible in a debug overlay (§37) even before real map art exists.

## Phase 4 — Movement

* [ ] Virtual joystick input.
* [ ] Movement intent packets sent to server; rendered position always derived from server-confirmed state (interpolated, never invented — see architecture doc §4).
* [ ] Other players', NPCs', and monsters' positions rendered from `ZC_NOTIFY_STANDENTRY`-family packets (to be byte-mapped from `clif.cpp` at this phase, per protocol doc §5.2).
* [ ] Map-to-map transitions (warps) trigger a fresh `CZ_ENTER`-style handshake to the new map.

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
