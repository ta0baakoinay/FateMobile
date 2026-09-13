# Fate MMO Mobile — Protocol Analysis

Source of truth: [`ta0baakoinay/FateRO`](https://github.com/ta0baakoinay/FateRO), commit `33d1e11` ("tool dealer"), cloned and inspected directly. This is a **near-stock rAthena** tree — the `src/custom/*` overrides only touch `atcommand`, `battle_config`, and `script` hooks. No packet-level customization exists anywhere in `src/login`, `src/char`, or `src/map`. That means the mobile client can target upstream rAthena's client protocol as documented here, verified against this repo's actual source rather than assumed.

**Everything below was read out of the source, not guessed.** Where a system has not yet been inspected in full byte-level detail (char list contents, inventory, skills, etc.), it is marked `TODO — VERIFY FROM SOURCE` rather than invented. Do not implement those without reading the corresponding `packets.hpp` / `*_clif.cpp` first.

## 1. Client version pin

```cpp
// src/config/packets.hpp:16
#define PACKETVER 20250716
```

The server is compiled for **PACKETVER 20250716** (`PACKETVER_RE`/renewal packet shapes). This number gates which struct layout is active for every conditional packet below (`#if PACKETVER >= ...`). Any future server rebuild with a different `PACKETVER` invalidates the offsets in this document — re-grep `src/config/packets.hpp` and `src/common/packets.hpp` before trusting old builds of this doc.

## 2. Transport

* Raw TCP, three independent sockets in sequence: **login → char → map**. No WebSocket, no HTTP, no TLS in stock rAthena.
* Byte order: **little-endian** on the wire (standard x86 rAthena convention) except for a couple of legacy fields called out below where the server explicitly re-swaps into big-endian (IPs) or applies an odd `ntows(htons(...))` double-swap (map server port in `HC_NOTIFY_ZONESVR`/`0xAC5` — this is not a mistake in this doc, it's in the source at `src/char/char_clif.cpp:869`).
* Every packet starts with a 2-byte little-endian opcode (`int16 packetType`). Fixed-length packets have no length field; variable-length packets carry a 2-byte `packetLength` immediately after the opcode.
* No packet encryption/XOR obfuscation is used by stock rAthena on login/char/map sockets. `CA_REQ_HASH`/`AC_ACK_HASH` (0x1DB/0x1DC) exist for optional password-hashing challenge flows but are not mandatory (see §3.3).
* Default ports (from `conf/*.conf` in the FateRO repo): login `6900` (`conf/login_athena.conf:13`). Char/map ports are the rAthena defaults (`6121`/`5121`) unless the server operator changed `conf/char_athena.conf` / `conf/map_athena.conf` — **verify against the live server's actual conf files before hardcoding**, since those weren't customized in a way `grep` would catch (they're runtime config, not compiled in).

## 3. Login server protocol

File: `src/login/loginclif.cpp`, struct defs: `src/common/packets.hpp`.

The login server uses a modern templated `PacketDatabase<login_session_data>` dispatch table (`LoginPacketDatabase`, `loginclif.cpp:483`), not the legacy raw `packet_db[]` array map/char still use. Every opcode below is registered there verbatim.

### 3.1 Client → Server: `CA_LOGIN` (0x0064) — the packet the mobile client should send

```cpp
struct PACKET_CA_LOGIN{
    int16  packetType;      // 0x0064
    uint32 version;         // client version int, e.g. matches PACKETVER-style client build id
    char   username[24];    // NAME_LENGTH, NUL-padded, plaintext
    char   password[24];    // NAME_LENGTH, NUL-padded, plaintext
    uint8  clienttype;      // client type flag (0 = normal); see clif.cpp clienttype usage
} __attribute__((packed));  // total size = 2+4+24+24+1 = 55 bytes
```

Handler: `logclif_parse_reqauth_raw<PACKET_CA_LOGIN>` (`loginclif.cpp:266`). Confirmed **plaintext password over the wire** — the server only MD5-hashes server-side if `login_config.use_md5_passwds` is set in `conf/login_athena.conf` (default: off, and this repo's conf wasn't customized to enable it). This is why Phase 1 must not skip TLS/VPN in production deployment — rAthena itself does not encrypt this socket. That's a server/protocol constraint, not something the mobile client can fix unilaterally (see `FATE_MMO_MOBILE_ARCHITECTURE.md` §Security for the mitigation: put login behind a TLS-terminating stunnel/VPN in front of port 6900 for production, which needs zero rAthena changes).

Other registered login opcodes exist for hashed/PCBang/channel/SSO variants (`CA_LOGIN2` 0x1DD, `CA_LOGIN3` 0x1FA, `CA_LOGIN4` 0x27C, `CA_LOGIN_PCBANG` 0x277, `CA_LOGIN_CHANNEL` 0x2B0, `CA_SSO_LOGIN_REQ` 0x825) — the mobile client does not need these; `CA_LOGIN` (0x0064) is accepted unconditionally regardless of `PACKETVER` and is the simplest correct path.

### 3.2 Server → Client: success — `AC_ACCEPT_LOGIN`

Because `PACKETVER (20250716) >= 20170315`, the **0xAC4** variant is active (`packets.hpp:41-63`):

```cpp
struct PACKET_AC_ACCEPT_LOGIN_sub{
    uint32 ip;
    uint16 port;
    char   name[20];
    uint16 users;
    uint16 type;
    uint16 new_;
    uint8  unknown[128];
} __attribute__((packed));   // 4+2+20+2+2+2+128 = 160 bytes per char-server entry

struct PACKET_AC_ACCEPT_LOGIN{
    int16  packetType;       // 0x0AC4
    int16  packetLength;     // total size, drives how many char_servers[] entries follow
    uint32 login_id1;
    uint32 AID;              // account id
    uint32 login_id2;
    uint32 last_ip;
    char   last_login[26];
    uint8  sex;
    char   token[17];        // WEB_AUTH_TOKEN_LENGTH = 16+1
    PACKET_AC_ACCEPT_LOGIN_sub char_servers[];  // one entry per configured char-server
} __attribute__((packed));   // fixed header = 64 bytes; num_char_servers = (packetLength - 64) / 160
```

`login_id1`, `AID` (account id), `login_id2`, and `sex` must be captured client-side — they are handed straight to the char-server in the next step (§4.1) as the auth handshake. The client picks one `char_servers[]` entry (usually the first, or by lowest `users`) and connects to that `ip:port`.

### 3.3 Server → Client: failure

* `AC_REFUSE_LOGIN` (0x083E, active since `PACKETVER >= 20120000`): `int16 packetType; uint32 error; char unblock_time[20];` — `error` is the standard rAthena login-refuse enum (bad password, account not found, banned, server closed, etc. — enum in `login.hpp`, not reproduced here; read it before building the "wrong password" UI copy).
* `SC_NOTIFY_BAN` (0x0081): `int16 packetType; uint8 result;` — sent when an account gets banned mid-session.

### 3.4 Login sequence diagram

```text
Android Client                          Login Server (:6900)
      |--- TCP connect ------------------------->|
      |--- CA_LOGIN (0x0064) -------------------->|
      |                                            | login_mmo_auth() against char/account tables
      |<-- AC_ACCEPT_LOGIN (0xAC4) ---------------| (on success: login_id1/2, AID, sex, char server list)
      |         or AC_REFUSE_LOGIN (0x083E) ------| (on failure)
      |--- TCP close (login) --------------------->|
      |--- TCP connect to chosen char-server ----->|
```

The client disconnects from the login socket after receiving the char-server list — it does not stay connected to port 6900.

## 4. Char server protocol (verified opcodes; struct-level detail is Phase 2 work)

File: `src/char/char_clif.cpp`. Char/map still use the **legacy `packet_db[]`/switch dispatch**, not the new templated system — expect more manual offset math here.

### 4.1 Client → Server: `CH_ENTER` (0x0065) — auth handshake into char-server

```text
offset  type   field
0       int16  packetType = 0x0065
2       uint32 account_id      (= AID from AC_ACCEPT_LOGIN)
6       uint32 login_id1       (from AC_ACCEPT_LOGIN)
10      uint32 login_id2       (from AC_ACCEPT_LOGIN)
16      uint8  sex             (from AC_ACCEPT_LOGIN)
                                total 17 bytes
```
Confirmed at `char_clif.cpp:775` (`chclif_parse_reqtoconnect`). Server replies immediately with a 4-byte echo of `account_id`, then validates against the login-server's shared auth table before allowing further char-server packets.

### 4.2 Other confirmed char-server opcodes (`char_clif.cpp:1665` switch table)

| Opcode | Name | Handler |
|---|---|---|
| 0x0065 | CH_ENTER | `chclif_parse_reqtoconnect` |
| 0x0066 | CH_SELECT_CHAR | `chclif_parse_charselect` |
| 0x0067 / 0x0970 / 0x0A39 | CH_MAKE_CHAR (version variants) | `chclif_parse_createnewchar` |
| 0x0068 / 0x01FB | CH_DELETE_CHAR (+legacy variant) | `chclif_parse_delchar` |
| 0x0187 | CH_KEEPALIVE (client sends every ~12s) | `chclif_parse_keepalive` |
| req char list | CH_CHARLIST_REQ | `chclif_parse_req_charlist` → `chclif_mmo_send099d` (0x099D family reply — **struct not yet catalogued, Phase 2 TODO**) |

### 4.3 Server → Client: map redirect after character select

Since `PACKETVER >= 20170315`, opcode is **0xAC5**, 156 bytes total (`char_clif.cpp:855-878`, `chclif_send_map_data`):

```text
offset  type    field
0       int16   packetType = 0x0AC5
2       uint32  char_id
6       char[16] map name (e.g. "prontera.gat", zero-padded)
22      uint32  map-server IP, network-byte-order (htonl)
26      uint16  map-server port — NOTE: server applies ntows(htons(port)) here, an
                                  intentional extra swap present in the source; treat
                                  this field as big-endian-then-something, verify by
                                  capturing a live packet before trusting either
                                  endianness blindly
28      uint8[128] unknown/zero-filled padding
```

Pre-20170315 builds use opcode `0x0071`, 28 bytes, no trailing padding — irrelevant here since this server is on 20250716, noted only so nobody copies a stale packet doc from an older rAthena guide.

### 4.4 CH_CHARLIST_REQ reply body, char creation reply, deletion confirmation

`TODO — VERIFY FROM SOURCE` before Phase 2. `chclif_mmo_send099d` and friends were located but not byte-mapped in this pass — do that when Phase 2 (character select) starts, per the incremental build rule; don't invent the struct now.

## 5. Map server protocol (verified entry point only; full packet catalogue is Phase 3+ work)

File: `src/map/clif.cpp`.

### 5.1 Client → Server: `CZ_ENTER` (0x0072 default form)

```text
offset  type    field
0       int16   packetType = 0x0072   (0x0436 = CZ_ENTER2 variant also exists; "various
                                        padded variants" per source comment at clif.cpp:11077)
2       uint32  account_id
6       uint32  char_id
10      uint32  login_id1   (auth code carried over from login/char handshake)
14      uint32  client_tick
18      uint8   sex
```
Confirmed at `clif.cpp:11077` (comment) and `clif_parse_WantToConnection` (`clif.cpp:11081`), which reads via the **versioned `packet_db[cmd].pos[]` offset table** rather than a fixed struct — meaning the exact byte offsets are resolved at runtime per-PACKETVER through `db/packet_db` client-version tables compiled into the binary, not a single fixed struct. **Do not hardcode offsets 2/6/10/14/18 blindly** — confirm the resolved `packet_db[0x0072]` positions for PACKETVER 20250716 specifically (grep the generated packet position table, or log `packet_db[cmd].pos[0..4]` at runtime) before wiring this up in Phase 3.

### 5.2 Everything past initial map entry

Movement, entity spawn (`ZC_NOTIFY_STANDENTRY` family), NPC dialogue, item pickup, inventory, skills, combat, chat, party, guild, storage, trade — all exist in `clif.cpp` (it's a ~27,000-line file) but were **not** individually catalogued in this pass. Each will be grepped and documented from source immediately before the phase that needs it (per §34 of the brief: build incrementally, don't front-load packet specs nobody's implementing yet). Do not let anything downstream assume a packet shape for these systems that hasn't been pulled from this file first.

## 6. What this means for the Android client roadmap

* **Phase 1 (this deliverable's PoC)** only needs §3: connect to login, send `CA_LOGIN`, parse `AC_ACCEPT_LOGIN`/`AC_REFUSE_LOGIN`. Fully specified above, byte-for-byte, from source.
* **Phase 2 (char select)** needs §4 — opcodes are confirmed, struct layout for the char list reply is not yet pulled and must be done before writing that screen.
* **Phase 3 (map load/movement)** needs §5 — entry opcode confirmed, exact offsets need a runtime/packet-table check because map server resolves them dynamically per client version rather than via a fixed struct like login does.
* Everything in §5.2 is explicitly unspecified pending the phase that needs it.

## 7. Production networking caveat (protocol, not client, problem)

Per §3.1, rAthena does not encrypt any of these three sockets. That is a property of the server protocol, not something to patch around in the Android client with a home-rolled cipher (which would just be security theater and would desync from the PC client's expectations). The correct fix, if internet-facing security matters here, is a TLS-terminating reverse proxy / stunnel in front of ports 6900/char/map, transparent to both PC and mobile clients — a deployment change, not a client or rAthena source change. Flagged here so it isn't silently "fixed" in the wrong layer later.
