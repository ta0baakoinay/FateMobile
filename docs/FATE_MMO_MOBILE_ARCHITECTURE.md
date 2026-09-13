# Fate MMO Mobile — Architecture

Companion documents: [`FATE_MMO_MOBILE_PROTOCOL.md`](FATE_MMO_MOBILE_PROTOCOL.md) (wire protocol, verified from the `FateRO` server source), [`FATE_MMO_MOBILE_ROADMAP.md`](FATE_MMO_MOBILE_ROADMAP.md) (phased build order).

## 1. What this is and isn't

Fate MMO Mobile is a native Android game client for the existing Fate MMO / rAthena server (`ta0baakoinay/FateRO`). It is not a WebView wrapper, not a new backend, and not a fork of the PC client's executable. It's an original client, built for Android, that speaks the same login/char/map TCP protocol the PC client speaks, against the same account/character/item/economy data.

```text
                         Fate MMO Server (FateRO / rAthena)
                    Login (:6900) -- Char (:6121) -- Map (:5121)
                                          |
                                     MySQL/MariaDB
                                          |
                +-------------------------+-------------------------+
                |                                                   |
          PC Client (existing)                          Fate MMO Mobile (this project)
       Traditional RO interface                       Modern touch MMORPG interface
```

Both clients are peers from the server's point of view — same accounts, same characters, same world state, same authority.

## 2. Layered module design

```text
+-----------------------------------------------------------+
|  Kotlin — Android Shell                                   |
|  Activities/Fragments, lifecycle, permissions, settings,   |
|  login/char-select UI (native Views/Compose), JNI bridge   |
+-----------------------------------------------------------+
                          | JNI
+-----------------------------------------------------------+
|  C++ — Game/Engine Layer (native/)                         |
|  networking | protocol | world | entities | maps |         |
|  combat | animation | audio | renderer (GLES)               |
+-----------------------------------------------------------+
                          |
                    OpenGL ES 3.x
```

Rationale for the split (per the brief's requirement in §2/§27): the render loop, entity simulation, and packet codec are latency- and allocation-sensitive and belong in C++ where object pooling and manual memory control are practical; everything that's inherently platform-integration (Activity lifecycle, permissions, on-screen keyboard, notifications, Play Store packaging) belongs in Kotlin because fighting the Android framework from native code buys nothing.

**Sequencing note (ties to the roadmap):** Phase 1's login proof-of-concept is implemented in pure Kotlin (`android/app/.../net/LoginClient.kt`), not JNI/C++, deliberately. The goal of Phase 1 is to prove the *protocol* — packet encoding, opcode handling, connection lifecycle — against the real server, which doesn't require a renderer or the performance ceiling C++ buys. Networking migrates into the `native/networking` + `native/protocol` C++ layer starting Phase 3 (map/movement), once packet volume and frequency make the JNI boundary and GC pauses actually matter. This is called out explicitly so nobody reads the Kotlin PoC as the final networking architecture — it's the first rung of the incremental build in §34 of the brief, and the migration point is a deliberate decision, not a shortcut left in by accident.

### 2.1 `native/` submodules

| Module | Responsibility |
|---|---|
| `networking` | TCP socket lifecycle (connect/reconnect/timeout), thread-safe send/receive queues, main-thread-safe event dispatch |
| `protocol` | Packet struct definitions + encode/decode, one file per server (login/char/map), mirroring `docs/FATE_MMO_MOBILE_PROTOCOL.md` exactly — this is the only place packet byte layouts are allowed to live |
| `world` | Map/cell data, walkability, layers, spatial queries |
| `entities` | Player/monster/NPC/pet/homunculus/effect entity components and pooling |
| `maps` | Map asset loading/caching, streaming, transitions |
| `combat` | Client-side presentation of combat (animations, damage numbers, HP bars) — **never computes damage/hit/crit**, see §5 |
| `animation` | Sprite/skeletal animation playback, blending |
| `audio` | BGM/SFX playback, pooling |
| `renderer` | OpenGL ES batching, culling, texture atlases, camera |
| `assets` | Runtime asset format loading (post asset-pipeline output, see §7) |

## 3. Networking architecture

```text
   Network Thread                Game/Main Thread
   --------------                ----------------
   TCP recv loop
        |
   Packet framing (length-prefixed
   or fixed-size per protocol.md)
        |
   Decode -> typed event  ---->  Thread-safe queue  ---->  Game state mutation
                                                                   |
                                                            Renderer reads
                                                            immutable-this-frame
                                                            state snapshot
```

Rules:
* The socket thread never touches game state directly and never calls into the renderer.
* All decoded packets become typed events pushed onto a lock-free (or mutex-guarded, measure first) queue; the game thread drains it once per frame.
* Outgoing packets (movement, chat, item use) are queued from the game/UI thread and flushed on the network thread — UI never blocks on `send()`.
* Reconnection: exponential backoff, capped; distinguish "server closed you out (banned/duplicate login)" from "connection dropped" — the former must not auto-retry.
* One connection at a time per server hop (login → char → map), matching the protocol doc's sequence; the client tears down the previous socket before opening the next, exactly like the PC client's server-hopping flow.

## 4. Client/server authority boundary

Non-negotiable per the brief §11, restated concretely against this codebase:

| Client (Android) owns | Server (rAthena) owns |
|---|---|
| Touch input, joystick, camera | Damage, hit/crit/miss |
| Local prediction of *movement rendering only* (interpolate between server-confirmed positions — never invent a new position the server hasn't acknowledged) | Position authority (server validates/corrects) |
| Rendering, animation, VFX playback | Item grants, zeny, drops |
| UI state (which panel is open, hotbar layout) | XP, leveling, stats |
| Audio | Skill resolution, cooldown enforcement |
| Sending intent packets (attack X, use skill Y on Z, move to (x,y)) | Monster AI/state |

If a feature would require the client to decide an outcome the server hasn't sent yet (e.g., "did that skill land"), the client shows a pending/optimistic-UI state at most (e.g., button goes on local cooldown immediately for responsiveness) but the authoritative result always comes from the next server packet and overrides the optimistic guess. This is the only form of client-side prediction permitted.

## 5. Rendering

* OpenGL ES (3.0 baseline, fall back to 2.0 feature set on old hardware) 2D/isometric sprite renderer.
* Texture atlases + sprite batching to minimize draw calls; frustum culling against the camera's visible map-cell bounds before adding anything to the batch.
* Object pooling for entities and transient effects (damage numbers, hit sparks) — zero per-frame heap allocation in the hot path once a pool is warmed.
* Target 60 FPS on mid/high-end hardware, with an adaptive quality mode (reduced effect density, lower-res atlas variant) for 2-4GB RAM devices per §26.

## 6. Android platform layer

* Kotlin, single Activity + Compose or Fragment-based screens (login, char select, in-game HUD as overlay above a `SurfaceView`/`GLSurfaceView` hosting the native renderer).
* JNI bridge is a thin call surface: lifecycle events (`onCreate`/`onPause`/`onResume`/`onDestroy` → native equivalents), input events (touch → native input queue), and UI-triggered actions (login submit, hotbar tap → native intent packet).
* Gradle multi-module: `:app` (Kotlin shell) depends on the native library built via CMake/NDK (`native/CMakeLists.txt`), producing a single `.so` linked into the APK.

## 7. Asset pipeline

```text
Original Fate MMO assets (GRF, sprites, maps, audio — operator-supplied, not bundled in this repo)
        |
tools/  (conversion scripts: GRF extraction -> texture atlas packing -> map data -> Android-friendly formats)
        |
assets/ (converted, Android-optimized: PNG/ETC2/ASTC atlases, binary map data, OGG/compressed audio)
        |
APK / on-demand asset pack
```

No copyrighted Ragnarok assets are bundled in this repository. `tools/` will hold conversion scripts the server operator runs against their own legally-held GRF/asset files; `assets/` stays empty (or holds only placeholder/dev art) in version control until populated locally. This is enforced structurally, not just by policy — see `.gitignore`.

## 8. Configuration & environments

`android/app/src/main/assets/server_config.json` (or a build-variant-specific resource) holds:

```json
{
  "environment": "development",
  "login": { "host": "", "port": 6900 },
  "notes": "Fill in per environment; never hardcode production IP as the only option."
}
```

Build flavors (`development`, `staging`, `production`) select which config ships, so a production release build isn't silently pointed at a LAN dev IP. No database credentials, admin/GM credentials, or server filesystem paths are ever placed in client code or resources — the client only ever knows a login host:port, exactly what the PC client's `clientinfo.xml` equivalent would expose.

## 9. Security posture

* Client never talks to MySQL directly — always Android → rAthena → DB, never Android → DB (§39 of the brief; this is enforced by the fact that the client has no DB driver at all, not just by convention).
* No credentials, secrets, or admin functionality embedded in the APK.
* See `FATE_MMO_MOBILE_PROTOCOL.md` §7 for the plaintext-login-socket caveat — that's a server-protocol property to mitigate at the deployment layer (TLS-terminating proxy in front of the three TCP ports), not something the client can unilaterally fix without desyncing from the documented protocol.

## 10. Repository layout

```text
FateMobile/
├── android/            Gradle project (Kotlin shell, JNI bridge, resources)
├── native/             C++ engine layer (see §2.1 table)
├── ui/                 Shared UI assets/specs not owned by android/res (icons, mockups)
├── tools/              Asset pipeline / conversion scripts
├── assets/             Converted runtime assets (gitignored except placeholders)
├── docs/               This document, protocol doc, roadmap
└── tests/              Native + Android test suites
```
