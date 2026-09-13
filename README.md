# Fate MMO Mobile

Native Android client for the **Fate MMO** rAthena server (server source: [`ta0baakoinay/FateRO`](https://github.com/ta0baakoinay/FateRO)). Kotlin Android shell + a C++/NDK engine layer (rendering, networking, world/entity simulation), speaking the real login/char/map TCP protocol against the existing server — not a WebView, not a new backend, not a wrapper around the PC client.

## Start here

1. [`docs/FATE_MMO_MOBILE_ARCHITECTURE.md`](docs/FATE_MMO_MOBILE_ARCHITECTURE.md) — module layout, networking model, client/server authority boundary, security posture.
2. [`docs/FATE_MMO_MOBILE_PROTOCOL.md`](docs/FATE_MMO_MOBILE_PROTOCOL.md) — the wire protocol, byte-mapped directly from the FateRO server source (not guessed).
3. [`docs/FATE_MMO_MOBILE_ROADMAP.md`](docs/FATE_MMO_MOBILE_ROADMAP.md) — phased build order and exit criteria for each phase.

## Current status: Phase 3 (partial) — Map Connection

The Android project in [`android/`](android/) builds a real, installable APK containing:

* A native login screen (`ui.LoginActivity`) — no WebView, no HTML.
* `net.LoginClient` — opens a raw TCP socket to the configured Fate MMO login server, sends `CA_LOGIN` (0x0064), and parses the server's actual `AC_ACCEPT_LOGIN` / `AC_REFUSE_LOGIN` / `SC_NOTIFY_BAN` response, exactly per the protocol doc.
* `net.CharServerClient` + `ui.CharSelectActivity` — on a successful login, connects to the char-server, drains the real character-list push (four packets: slot summary, character array, page notify, block list), and renders a text-only list (name/job/level) with working Play, Create, and Delete against the live server's actual char-server protocol — including using the account's **birthdate**, not an email, for deletion, per this server's actual config.
* `net.MapServerClient` + `ui.MapActivity` — selecting a character now actually connects to the map-server and performs the real `CZ_ENTER` handshake. Getting this opcode right required tracing the server's runtime packet table rather than trusting a stale source comment: it's `0x0436` (23 bytes) for this build, not the `0x0072` a generic rAthena reference would suggest (that opcode is reassigned to a skill-use packet for modern clients). The screen decodes and displays the server-confirmed spawn map/coordinates — a debug view, not a game view.
* An NDK/CMake-linked native library (`native/`) with a stub JNI call, proving the C++ toolchain is wired before real engine code lands there in later phases.

It does **not** yet render anything, spawn the character into a world, stream inventory/nearby entities, or handle movement — the client deliberately stops short of sending `CZ_NOTIFY_ACTORINIT` ("I've finished loading"), since nothing exists yet to handle the flood of gameplay packets that would trigger. See the roadmap's Phase 3 entry for the honest split between what's done (3a: connect + confirm spawn) and what isn't (3b: actually entering the world, rendering, native networking migration).

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
tools/     Asset pipeline scripts (GRF → Android-optimized formats) — operator supplies their own assets, none are bundled here
assets/    Converted runtime assets (gitignored; build output of tools/)
docs/      Architecture, protocol, and roadmap documents
tests/     Native + Android test suites
```

## Server authority

The rAthena server is always authoritative for combat, items, economy, XP, and character state. The Android client is responsible for input, rendering, UI, networking, and audio — never for gameplay outcomes. See [`docs/FATE_MMO_MOBILE_ARCHITECTURE.md`](docs/FATE_MMO_MOBILE_ARCHITECTURE.md) §4 for the exact boundary.
