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

## Phase 2 — Character Selection

Depends on: byte-mapping `chclif_mmo_send099d`'s reply and the char-server auth handshake's full response (currently opcode-confirmed only, per protocol doc §4.4 `TODO`).

* [ ] Grep `char_clif.cpp` for the exact char-list reply struct (0x099D family) before writing any UI against it.
* [ ] Char-server connect (`CH_ENTER` 0x0065) using `login_id1/login_id2/AID/sex` captured in Phase 1.
* [ ] Character list screen: sprite, name, job, base/job level; Play/Create/Delete/Back.
* [ ] Character create (`CH_MAKE_CHAR`) and delete (`CH_DELETE_CHAR`) flows, including whatever confirmation step the live server enforces (email/birthdate confirmation for delete is common in rAthena — verify against this server's actual config, don't assume).

Exit criteria: an existing Fate MMO character (created on PC) is visible and selectable from the Android client, and a new character created on Android is visible on the PC client's char-select screen.

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
