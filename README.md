# Fate MMO Mobile

Native Android client for the **Fate MMO** rAthena server (server source: [`ta0baakoinay/FateRO`](https://github.com/ta0baakoinay/FateRO)). Kotlin Android shell + a C++/NDK engine layer (rendering, networking, world/entity simulation), speaking the real login/char/map TCP protocol against the existing server — not a WebView, not a new backend, not a wrapper around the PC client.

## Start here

1. [`docs/FATE_MMO_MOBILE_ARCHITECTURE.md`](docs/FATE_MMO_MOBILE_ARCHITECTURE.md) — module layout, networking model, client/server authority boundary, security posture.
2. [`docs/FATE_MMO_MOBILE_PROTOCOL.md`](docs/FATE_MMO_MOBILE_PROTOCOL.md) — the wire protocol, byte-mapped directly from the FateRO server source (not guessed).
3. [`docs/FATE_MMO_MOBILE_ASSETS.md`](docs/FATE_MMO_MOBILE_ASSETS.md) — the client-side asset formats (GRF/GAT/GND/SPR), verified against the operator's real Fate MMO client files.
4. [`docs/FATE_MMO_MOBILE_ROADMAP.md`](docs/FATE_MMO_MOBILE_ROADMAP.md) — phased build order and exit criteria for each phase.

## Current status: Phase 3 — map handshake + real rendering + local movement

The Android project in [`android/`](android/) builds a real, installable APK containing:

* A native login screen (`ui.LoginActivity`) — no WebView, no HTML.
* `net.LoginClient` — opens a raw TCP socket to the configured Fate MMO login server, sends `CA_LOGIN` (0x0064), and parses the server's actual `AC_ACCEPT_LOGIN` / `AC_REFUSE_LOGIN` / `SC_NOTIFY_BAN` response, exactly per the protocol doc.
* `net.CharServerClient` + `ui.CharSelectActivity` — on a successful login, connects to the char-server, drains the real character-list push (four packets: slot summary, character array, page notify, block list), and renders a text-only list (name/job/level) with working Play, Create, and Delete against the live server's actual char-server protocol — including using the account's **birthdate**, not an email, for deletion, per this server's actual config.
* `net.MapServerClient` + `ui.MapActivity` — selecting a character connects to the map-server and performs the real `CZ_ENTER` handshake (opcode `0x0436` for this build — tracing the server's runtime packet table found the generic-guide answer, `0x0072`, is reassigned to a skill-use packet on modern clients) and decodes the server-confirmed spawn position.
* **Real map + character rendering** (`world.GameMapView`, Canvas-based): for the Prontera starting map, the app bundles a top-down ground image and character sprite converted from the operator's actual Fate MMO client files (`tools/grf/`, a from-scratch GRF/GAT/GND/SPR reader built and verified this pass — see `FATE_MMO_MOBILE_ASSETS.md`), and shows the real character sprite at the real server-assigned spawn coordinates, movable with a virtual joystick constrained by the real wall-collision grid. **This movement is client-local only** — it isn't sent to the server yet (see below).
* An NDK/CMake-linked native library (`native/`) with a stub JNI call, proving the C++ toolchain is wired before real engine code lands there in later phases.

**Deliberately not done**: the client never sends `CZ_NOTIFY_ACTORINIT` ("I've finished loading"), which is what triggers the server to actually spawn the character and stream inventory/nearby-entities/skills/etc. — safely handling that flood needs a server→client packet-length map this pass didn't build (unlike client→server packets, there's no central length registry to extract from source). So: no server-synced movement, no other players/monsters, no combat, no inventory yet. Also not done: the two-tier "download what's needed now / download all" asset delivery (needs server-hosted asset packs that don't exist yet — assets are bundled directly in the APK for now), ACT-driven directional sprite animation (static idle frame only), and maps other than Prontera. See the roadmap's Phase 3 entry (3a–3d) for the full, honest breakdown, and note that **none of this has been run against a live server or a real device yet** — the byte-level parsing is verified against real files/source, but the actual APK hasn't been installed and tested by a human.

## Building

```
cd android
./gradlew assembleDevelopmentDebug
```

Requires Android Studio (or a standalone Android SDK + NDK) — `local.properties` with `sdk.dir` is created automatically on first Android Studio sync, or set `ANDROID_HOME`/`ANDROID_SDK_ROOT` for CLI builds. Before logging in, point `android/app/src/main/assets/server_config_development.json` at your actual Fate MMO login server (the emulator default, `10.0.2.2:6900`, only reaches a server running on your host machine).

## Repository layout

```
android/   Gradle project — Kotlin shell, JNI bridge, resources
native/    C++ engine layer (networking, protocol, world, entities, maps, combat, animation, audio, renderer, assets)
ui/        Shared UI assets/specs, mockups
tools/     Asset pipeline (tools/grf/ — a real GRF/GAT/GND/SPR reader + map rasterizer, Java/JDK, no external deps). Run against the operator's own client files; none of those source GRF/BMP/SPR files are bundled in this repo
assets/    Converted runtime assets (gitignored; build output of tools/) — NOT the same as android/app/src/main/assets/, which holds the app's actual bundled per-map assets and IS tracked
docs/      Architecture, protocol, and roadmap documents
tests/     Native + Android test suites
```

## Server authority

The rAthena server is always authoritative for combat, items, economy, XP, and character state. The Android client is responsible for input, rendering, UI, networking, and audio — never for gameplay outcomes. See [`docs/FATE_MMO_MOBILE_ARCHITECTURE.md`](docs/FATE_MMO_MOBILE_ARCHITECTURE.md) §4 for the exact boundary.
