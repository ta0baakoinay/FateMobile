# Fate MMO Mobile — Protocol Analysis

Source of truth: [`ta0baakoinay/FateRO`](https://github.com/ta0baakoinay/FateRO), commit `33d1e11` ("tool dealer"), cloned and inspected directly. This is a **near-stock rAthena** tree — the `src/custom/*` overrides only touch `atcommand`, `battle_config`, and `script` hooks. No packet-level customization exists anywhere in `src/login`, `src/char`, or `src/map`. That means the mobile client can target upstream rAthena's client protocol as documented here, verified against this repo's actual source rather than assumed.

**Everything below was read out of the source, not guessed.** Login (§3) and char-server (§4, including the full character list/select/create/delete flow) are fully byte-mapped as of Phase 2. Where a system has not yet been inspected in full byte-level detail (map/movement/inventory/skills/etc.), it is marked `TODO — VERIFY FROM SOURCE` rather than invented. Do not implement those without reading the corresponding `packets.hpp` / `*_clif.cpp` first.

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

## 4. Char server protocol (fully verified for Phase 2)

File: `src/char/char_clif.cpp`, struct defs also drawn from `src/char/packets.hpp`. Char/map still use the **legacy `packet_db[]`/switch dispatch**, not the new templated system — expect more manual offset math here. Every packet in this section is confirmed from source; nothing here is guessed.

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
Confirmed at `char_clif.cpp:775` (`chclif_parse_reqtoconnect`).

**Server's reply is NOT opcode-prefixed** — it is a bare 4-byte little-endian `account_id` echo (`WFIFOL(fd,0)=account_id; WFIFOSET(fd,4);`, no `packetType` field at all). Read exactly 4 bytes first, before falling back to the normal opcode-prefixed frame reader for everything that follows. This one-off is easy to miss and will desync the whole char-server stream if skipped.

After that echo, the char-server internally round-trips to the login-server (opcodes 0x2712/0x2716/0x2717/0x2713, all server-to-server, invisible to the client) before pushing anything else. Two outcomes follow:

* **Failure** — `HC_REFUSE_ENTER` (**0x006C**): `int16 packetType; uint8 errCode;` (3 bytes). Sent via `chclif_reject()` (`char_clif.cpp:1558`) if the server is shutting down or the login-server round-trip rejects the account.
* **Also possible on failure**: `chclif_send_auth_result` reuses opcode **0x0081** on the char-server connection (distinct meaning from `SC_NOTIFY_BAN` on the *login* connection — same wire opcode, different server, different semantics): `int16 packetType; uint8 result;` (3 bytes), `result` 1–15 per the comment at `char_clif.cpp:472` (1=server closed, 2=already logged in elsewhere, 3=client/server time gap, 4/7=overpopulated, 5=underage, 6=unpaid, 8=already online, 9=cybercafe IP cap, 10=out of playtime, 11=suspended, 12–14=billing/IP-lock related, 15=generic disconnect). Sent e.g. from `char_auth_ok()` when the account is already connected/mid-selection elsewhere.
* **Success** — the char-list push sequence, §4.2 below.

### 4.2 Server → Client: character list push (auto-sent after successful `CH_ENTER`, no request needed)

`chclif_mmo_char_send()` (`char_clif.cpp:456`) fires automatically once account data comes back from the login-server — the client does **not** need to send a "give me the list" packet on initial login (that packet, `CH_CHARLIST_REQ` / `chclif_mmo_send099d`, exists only for later re-pagination and is out of scope here). Four packets arrive in this exact order; **the client must read and account for all four before doing anything else on this socket**, or the next read (e.g. a char-select response) will desync against leftover bytes:

**1) `HC_ACCEPT_ENTER_CHANGE_SLOT3` / slot-summary, opcode 0x082D, fixed 29 bytes** (`chclif_mmo_send082d`):
```text
offset  type    field
0       int16   packetType = 0x082D
2       int16   packetLength = 29 (fixed)
4       uint8   normal_slot      (= MIN_CHARS)
5       uint8   premium_slot     (= sd->chars_vip)
6       uint8   billing_slot     (= sd->chars_billing)
7       uint8   producible_slot  (= sd->char_slots — the account's actual usable slot count)
8       uint8   valid_slot       (= MAX_CHARS)
9       uint8[20] unused, zero-filled
```
Use `producible_slot`/`valid_slot` from this packet at runtime rather than hardcoding rAthena's `MIN_CHARS`/`MAX_CHARS` build constants — they're compile-time values on the server (`src/common/mmo.hpp`, 9/12/15 depending on other build flags) that this client has no reliable way to read otherwise, and the server already hands them over here.

**2) `HC_ACCEPT_ENTER`, opcode 0x006B, variable length** (`chclif_mmo_send006b`, `char_clif.cpp:388`):
```text
offset  type    field
0       int16   packetType = 0x006B
2       int16   packetLength           (total, header + N character entries)
4       uint8   MaxSlots               (= MAX_CHARS)
5       uint8   AvailableSlots         (= MIN_CHARS — a build constant echoed here, NOT
                                         producible_slot; use the 0x082D value instead if
                                         you need the account's real usable-slot count)
6       uint8   PremiumSlots           (= MIN_CHARS + sd->chars_vip)
7       uint8[20] unknown, zero-filled
27      CHARACTER_INFO[N] characters   (N = number of characters that actually exist on
                                         the account, NOT padded to MaxSlots — see below)
```

Each `CHARACTER_INFO` entry is **exactly 175 bytes** for PACKETVER 20250716 (`src/char/packets.hpp:17`, resolved for this build's `#if PACKETVER >= ...` chain — recompute this if the server is ever rebuilt for a different PACKETVER, the struct is version-conditional throughout):

```text
offset  size  type     field
0       4     uint32   GID              (char_id)
4       8     int64    exp
12      4     int32    money            (zeny)
16      8     int64    jobexp
24      4     int32    joblevel
28      4     int32    bodystate
32      4     int32    healthstate
36      4     int32    effectstate
40      4     int32    virtue
44      4     int32    honor
48      2     int16    jobpoint
50      8     int64    hp
58      8     int64    maxhp
66      8     int64    sp
74      8     int64    maxsp
82      2     int16    speed
84      2     int16    job              (class/job id)
86      2     int16    head
88      2     int16    body
90      2     int16    weapon
92      2     int16    level            (base level)
94      2     int16    sppoint
96      2     int16    accessory
98      2     int16    shield
100     2     int16    accessory2
102     2     int16    accessory3
104     2     int16    headpalette
106     2     int16    bodypalette
108     24    char[24] name
132     1     uint8    Str
133     1     uint8    Agi
134     1     uint8    Vit
135     1     uint8    Int
136     1     uint8    Dex
137     1     uint8    Luk
138     1     uint8    CharNum          (slot index)
139     1     uint8    hairColor
140     2     int16    bIsChangedCharName
142     16    char[16] mapName          (last map, e.g. "prontera")
158     4     int32    DelRevDate       (pending-deletion timestamp, 0 if not scheduled)
162     4     int32    robePalette
166     4     int32    chr_slot_changeCnt
170     4     int32    chr_name_changeCnt
174     1     uint8    sex
                                         total: 175 bytes
```
Only characters that actually exist in the DB are serialized (`char_mmo_chars_fromsql`, `char.cpp:898` — loops over SQL rows, not over slot numbers), so `N = (packetLength - 27) / 175`, and an account with zero characters sends a 27-byte 0x006B with no trailing entries at all.

**3) `HC_CHARLIST_NOTIFY`, opcode 0x09A0, fixed 6 bytes for this PACKETVER** (`chclif_charlist_notify`, `char_clif.cpp:367`):
```text
offset  type    field
0       int16   packetType = 0x09A0
2       int32   totalPageCount   (pagination hint for the classic client's multi-page char list; irrelevant to a client that just renders whatever 0x006B sent)
```
(The 10-byte variant with an extra `char_slots` field only applies to `20151001 <= PACKETVER < 20180103` — this server is past that window, so it's the 6-byte form. Still must be read and discarded to keep the stream in sync.)

**4) `HC_BLOCK_CHARACTER`, opcode 0x020D, variable length, minimum 4 bytes** (`chclif_block_character`, `char_clif.cpp:1429`):
```text
offset  type      field
0       int16     packetType = 0x020D
2       int16     packetLength         (4 if no blocked/banned characters — the common case)
4       [ (char_id.L, expire_date_string[20]) ]*   one 24-byte entry per character with an active unban_time
```
Safe to read-and-discard for Phase 2 (no UI surfaces temporary character bans yet) — but the bytes **must** still be consumed to keep the socket framed correctly for whatever request the client sends next.

**PIN code note (currently inert on this server):** if `pincode_enabled: yes` in `conf/char_athena.conf` (the shipped default in this repo is **`pincode_enabled: no`** — verify the live server's actual conf before assuming either way), an additional `HC_ACK_PINCODE` (**0x08B9**, 12 bytes: `int16 packetType; uint32 seed; uint32 account_id; uint16 state;`, state enum in `char.hpp:113` — `PINCODE_OK=0, PINCODE_ASK=1, PINCODE_NEW=4, PINCODE_PASSED=7`) arrives after the four packets above, and the client must answer with `CH_SELECT_ACCESSIBLE_MAPNAME`-adjacent pincode packets before it's allowed to select a character. **Not implemented**: the client should recognize 0x08B9 (so it doesn't desync/hang if an operator later enables pincode) but Phase 2 does not build a pincode entry screen, since it's off by default and building it now would be implementing a system "not actually used by Fate MMO" per the brief's incremental-build rule. Revisit if the live server has it enabled.

### 4.3 Client → Server: `CH_SELECT_CHAR` (0x0066)

```text
offset  type   field
0       int16  packetType = 0x0066
2       uint8  slot            (CharNum from the chosen CHARACTER_INFO entry)
                                total 3 bytes
```
Confirmed at `char_clif.cpp:1042` (`chclif_parse_charselect`).

Server replies with one of:
* **Success** — `HC_NOTIFY_ZONESVR` / map redirect, opcode **0xAC5**, 156 bytes (already documented below, §4.4 — this is the same packet regardless of whether it's reached via login→char→select).
* **Failure** — `HC_REFUSE_ENTER` (0x006C, 3 bytes, same struct as §4.1) — e.g. character scheduled for deletion, or a forged/stale slot index.
* **No map-server available** — `HC_NOTIFY_ACCESSIBLE_MAPNAME` (opcode 0x0840, `chclif_accessible_maps`): `int16 packetType; int16 packetLength; { int32 status; char map[MAP_NAME_LENGTH_EXT]; }[]`. Operational edge case (no map-server currently registered with char-server) — worth recognizing so the client doesn't hang, not worth a dedicated UI in Phase 2.

### 4.4 Server → Client: map redirect after character select

Since `PACKETVER >= 20170315`, opcode is **0xAC5**, 156 bytes total (`char_clif.cpp:855-878`, `chclif_send_map_data`):

```text
offset  type    field
0       int16   packetType = 0x0AC5
2       uint32  char_id
6       char[16] map name (e.g. "prontera.gat", zero-padded)
22      uint32  map-server IP, network-byte-order (htonl)
26      uint16  map-server port, plain little-endian — the source applies
                                  ntows(htons(port)) here (char_clif.cpp:871), which
                                  looks alarming but is a double byte-swap that cancels
                                  out (ntows() is just a bswap16, confirmed at
                                  socket.cpp:1684); the source comment "[!] LE byte
                                  order here [!]" confirms this was intentional/known,
                                  not a bug. Decode it exactly like every other
                                  little-endian uint16 in this document.
28      uint8[128] unknown/zero-filled padding
```

Pre-20170315 builds use opcode `0x0071`, 28 bytes, no trailing padding — irrelevant here since this server is on 20250716, noted only so nobody copies a stale packet doc from an older rAthena guide.

### 4.5 Client → Server: `CH_MAKE_CHAR` (character creation)

Three opcode variants exist server-side (`chclif_parse_createnewchar`, `char_clif.cpp:1181`), selected by client version. Since PACKETVER 20250716 ≥ 20151001, this client uses **0x0A39, fixed 36 bytes** (the other two — 0x0970/31 bytes, 0x0067/37 bytes — are for older clients and not used here, listed only so nobody wires the wrong one):

```text
offset  type      field
0       int16     packetType = 0x0A39
2       char[24]  name
26      uint8     slot
27      uint16    hair_color
29      uint16    hair_style
31      uint16    start_job         (server currently hardcodes str/agi/vit/int/dex/luk=1
                                      regardless of what's sent for this PACKETVER branch —
                                      see char_clif.cpp:1211-1216 — so there is no point
                                      sending custom stat rolls even if a UI offered them)
33      uint16    unknown (send 0)
35      uint8     sex
                                      total 36 bytes
```

Server replies with one of:
* **Failure** — opcode **0x006E**, 3 bytes: `int16 packetType; uint8 errCode;` — `0x00`=name already exists, `0xFF`=character creation disabled server-side (`char_new: no`) or generic denial, `0x01`=underage, `0x03`=not eligible for that slot (premium/billing slot not owned).
* **Success** — opcode **`HEADER_HC_ACCEPT_MAKECHAR`, resolved to 0x0B6F** for this PACKETVER (`PACKETVER_RE_NUM >= 20211103` branch in `char/packets.hpp:112-126` — **not** the classic `0x006D` a generic rAthena guide would show; this is genuinely version-gated and was confirmed against this exact build, not assumed), **fixed 177 bytes**: `int16 packetType` followed immediately by one 175-byte `CHARACTER_INFO` entry (§4.2) for the newly created character — no separate `packetLength` field, since the size is constant for a given compiled PACKETVER.

`conf/char_athena.conf` on this repo ships `char_new: yes` (creation enabled) by default — verify the live server hasn't flipped it before assuming `0xFF` means something else.

### 4.6 Client → Server: `CH_DELETE_CHAR` (character deletion)

The server actually registers **two** independent deletion flows (`char_clif.cpp:1665-1674`): the classic single round-trip (`0x0068`/`0x01FB`) and a separate two-step "reserve then confirm" flow (opcodes 0x0827/0x0829, not detailed here since it's not needed — see below for why). Real official clients pick one or the other based on an internal `langtype` the server source doesn't expose; since this is an **original** client, not a langtype-accurate reimplementation of the official EXE, it's free to pick whichever the server accepts — the simpler single round-trip:

```text
offset  type      field
0       int16     packetType = 0x0068
2       uint32    char_id
6       char[40]  confirmation code — see below for which one this server expects
                                       total 46 bytes
```
Confirmed at `char_clif.cpp:1307` (`chclif_parse_delchar`). (`0x01FB` is a 56-byte legacy variant for older clients — not used here.)

**Which confirmation code goes in that 40-byte field is a live server config value, not a client choice**: `conf/char_athena.conf`'s `char_del_option` (this repo ships **`char_del_option: 2`** = birthdate) selects between e-mail (`1`) and birthdate (`2`) or either (`3`) — `chclif_delchar_check()` (`char_clif.cpp:626`) compares against `sd->birthdate+2` (the account's stored birthdate, `YYMMDD`, century stripped) when the birthdate bit is set. **On this server's shipped default config, send the account's birthdate as `YYMMDD` (6 ASCII chars, NUL-padded to 40) in that field, not an email address.** Verify the live server's actual `char_del_option` before assuming this holds — it's operator-configurable and easy to have changed.

Server replies with one of:
* **Failure** — `HC_REFUSE_DELETECHAR` (opcode **0x0070**, 3 bytes: `int16 packetType; uint8 errCode;`) — `0`=wrong email/birthdate, `1`=invalid slot/character not found, `2`=character still in a party or guild (`char_del_restriction` on this server defaults to `3` = blocks deletion for either).
* **Success** — `HC_ACCEPT_DELETECHAR` (opcode **0x006F**, 2 bytes, header only, no body).

### 4.7 Char-server packet summary

| Direction | Opcode | Name | Size | §
|---|---|---|---|---|
| → | 0x0065 | CH_ENTER | 17 | 4.1 |
| ← | (none) | account_id echo | 4 (no opcode) | 4.1 |
| ← | 0x006C | HC_REFUSE_ENTER | 3 | 4.1, 4.3 |
| ← | 0x0081 | auth result (char-server context) | 3 | 4.1 |
| ← | 0x082D | slot summary | 29 | 4.2 |
| ← | 0x006B | HC_ACCEPT_ENTER (char list) | 27 + 175×N | 4.2 |
| ← | 0x09A0 | HC_CHARLIST_NOTIFY | 6 | 4.2 |
| ← | 0x020D | HC_BLOCK_CHARACTER | 4 + 24×N | 4.2 |
| ← | 0x08B9 | HC_ACK_PINCODE (inert — pincode disabled by default) | 12 | 4.2 |
| → | 0x0066 | CH_SELECT_CHAR | 3 | 4.3 |
| ← | 0x0AC5 | map redirect | 156 | 4.4 |
| ← | 0x0840 | HC_NOTIFY_ACCESSIBLE_MAPNAME (no map-server available) | 4 + 20×N | 4.3 |
| → | 0x0A39 | CH_MAKE_CHAR | 36 | 4.5 |
| ← | 0x006E | make-char refused | 3 | 4.5 |
| ← | 0x0B6F | HC_ACCEPT_MAKECHAR | 177 | 4.5 |
| → | 0x0068 | CH_DELETE_CHAR | 46 | 4.6 |
| ← | 0x0070 | HC_REFUSE_DELETECHAR | 3 | 4.6 |
| ← | 0x006F | HC_ACCEPT_DELETECHAR | 2 | 4.6 |

## 5. Map server protocol (connection handshake fully verified for Phase 3; gameplay packets remain later-phase work)

File: `src/map/clif.cpp` (~27,000 lines). The Phase 1 draft of this section used the opcode from a stale doxygen-style comment (`0x0072`) without checking the server's actual runtime packet table — the roadmap flagged this as unverified, and it was right to: **`0x0072` is wrong for this build**. It's been reassigned to `clif_parse_UseSkillToId` for modern clients (see §5.1). This section replaces that guess with what's actually registered for PACKETVER 20250716.

### 5.0 Two version-dependent wire-format quirks that affect every map-server packet

Unlike login/char (fixed structs), the map server resolves each opcode's meaning through a **runtime table** (`packet_db[]`, populated once at startup by `#include`-ing `src/map/clif_packetdb.hpp` then `src/map/clif_shuffle.hpp` into `packetdb_readdb()`, `clif.cpp:26753`). Two historical anti-bot mechanisms live in that table and must be resolved for the exact PACKETVER before trusting any opcode number:

**(a) Packet ID shuffling.** Official kRO clients up to 2018-03-07 shipped with a *per-build-random* mapping from semantic packet to wire opcode (a different mapping baked into every dated client executable) — `clif_shuffle.hpp` encodes each one as an exact `#if PACKETVER == YYYYMMDD` block. **Clients after that date stopped shuffling** — `clif_shuffle.hpp`'s own comment says so directly, and the file's final `#elif PACKETVER > 20180307` block (which applies to our build) uses stable, non-random opcodes. So for PACKETVER 20250716, ignore every dated block above; only the final catch-all block matters.

**(b) Packet ID XOR obfuscation.** Separately, `src/config/packets.hpp:44-62` enables `PACKET_OBFUSCATION` for any `PACKETVER >= 20110817` (ours qualifies) — the 2-byte opcode of every client→server packet is XORed with a rolling key derived from `clif_cryptKey[0..2]` (`clif.cpp:26622-26696`; only the opcode is obfuscated, never the payload). **In practice this is also a no-op for this build**: `src/map/clif_obfuscation.hpp:421-422` hardcodes `clif_cryptKey = {0, 0, 0}` for any `PACKETVER > 20180307`, and XORing with a key derived from all-zero constants (`(0*0+0)>>16 & 0x7FFF = 0`) is the identity function. **Net effect: no cipher to implement.** This was verified by tracing the exact constant, not assumed — worth stating plainly since it's the kind of thing that's tempting to half-remember from an old rAthena guide and get backwards.

Both quirks point the same direction: for this specific PACKETVER, the *mechanisms* exist in the source but their *effects* are inert. Don't let that generalize to "map protocol needs no special handling" for a different server build — if FateRO is ever recompiled for an older PACKETVER, both of these come back to life and this section would need re-deriving.

### 5.1 Client → Server: `CZ_ENTER` (WantToConnection) — opcode `0x0436`, 23 bytes

Resolved by tracing `packetdb_readdb()`'s two `#include`s in order (`clif_packetdb.hpp` first, `clif_shuffle.hpp` second — later registrations for the same opcode win). `clif_shuffle.hpp`'s final `#elif PACKETVER > 20180307` block (`clif_shuffle.hpp:4745-4748`) is what actually applies:

```cpp
#if PACKETVER_RE_NUM >= 20211103 || PACKETVER_MAIN_NUM >= 20220330
    parseable_packet( 0x0436, 23, clif_parse_WantToConnection, 2, 6, 10, 14, 22 );
#else
    parseable_packet( 0x0436, 19, clif_parse_WantToConnection, 2, 6, 10, 14, 18 );
#endif
```
`PACKETVER_RE_NUM` = `PACKETVER` = 20250716 ≥ 20211103, so the **23-byte** form applies:

```text
offset  type    field
0       int16   packetType = 0x0436
2       uint32  account_id
6       uint32  char_id
10      uint32  login_id1        (the auth code — AC_ACCEPT_LOGIN's login_id1, carried
                                   through CH_ENTER, unchanged through this handshake)
14      uint32  client_tick      (any monotonic value the client likes; server doesn't
                                   validate it beyond existing, see clif.cpp:11098)
18      uint8[4] unknown         (gap between the 19-byte legacy form and this one —
                                   not read by packet_db[0x0436].pos[], safe to zero-fill)
22      uint8   sex
                                  total 23 bytes
```
`clif_parse_WantToConnection_sub` (`clif.cpp:11049`) validates `account_id` against `START_ACCOUNT_NUM..END_ACCOUNT_NUM`, `char_id > 0`, and `sex` is `0` or `1` — all read through `packet_db[cmd].pos[]`, so these offsets are exactly what the live server checks against, not an assumption.

### 5.2 Server → Client: session id echo, opcode `0x0283`, 6 bytes

Sent immediately once `CZ_ENTER` passes validation and a session object is created (`clif.cpp:11169-11174`, active since `PACKETVER >= 20070521`): `int16 packetType; uint32 sessionId;` (this is an internal session pointer/id echo, not the account id — don't assume it equals `account_id`).

### 5.3 What happens between the echo and the real answer: an invisible server-to-server round trip

Right after sending the 0x0283 echo, the map server calls `chrif_authreq(sd, false)` (`clif.cpp:11176`), which asks the **char-server** to confirm the auth token this session presented (the same `login_id1`/`login_id2` pair minted back at login) — entirely server-to-server, invisible on the wire the client sees. **The client's next packet on this socket only arrives after that round trip resolves** — there's no intermediate "please wait" packet, so a client implementation must simply keep the socket read blocked/pending rather than treating silence as a hang after a fixed short timeout the way the login/char sockets can.

### 5.4 Server → Client: the real answer — `ZC_ACCEPT_ENTER` (success) or `ZC_REFUSE_ENTER` (failure)

**Success — `ZC_ACCEPT_ENTER`, opcode `0x02EB`, 13 bytes** (`clif_authok`, `clif.cpp:1060`; struct resolved for `PACKETVER >= 20160330`, `src/map/packets.hpp:554-563`):
```text
offset  type     field
0       int16    packetType = 0x02EB
2       uint32   startTime        (server tick at spawn, cosmetic)
6       uint8[3] posDir           (packed X/Y/direction — see §5.5 for the bit layout)
9       uint8    xSize            (ignored by the server itself per its own comment)
10      uint8    ySize            (ignored)
11      uint16   font
                                   total 13 bytes
```

**Failure — `ZC_REFUSE_ENTER`, opcode `0x0074`, 3 bytes** (`clif_authrefuse`, `clif.cpp:1088`): `int16 packetType; uint8 errorCode;` — `0`=client type mismatch, `1`=ID mismatch, `2`=mobile out of available time, `3`=mobile already logged in, `4`=mobile waiting state.

**Also possible: `SC_NOTIFY_BAN`, opcode `0x0081`, 3 bytes** — this is the *third* distinct server context reusing wire opcode `0x0081` with its own meaning (login's ban notice, char-server's auth-result, and now map's kick/disconnect notice — three different sockets, three different structs, same number; never assume opcode uniqueness across server types). Same `int16 packetType; uint8 errorCode;` shape, error table at `clif.cpp:1100-1120` (0=BAN_UNFAIR, 1=server closed, 2=already logged in, 3=timeout, 4=server full, 5=underage, and a long tail of billing/regional codes not relevant to this server).

### 5.5 The 3-byte packed position+direction format (`WBUFPOS`, `clif.cpp:177`)

This exact bit layout recurs throughout the map protocol (every entity spawn/move packet uses it, not just `ZC_ACCEPT_ENTER`) — worth documenting once, precisely, from the actual packing function rather than a remembered approximation:

```cpp
// encode (server-side, clif.cpp:177):
byte0 = (x >> 2) & 0xFF
byte1 = ((x << 6) | ((y >> 4) & 0x3F)) & 0xFF
byte2 = ((y << 4) | (dir & 0x0F)) & 0xFF
```
Decoding (client-side, the inverse):
```text
x   = ((byte0 << 2) | (byte1 >> 6)) & 0x3FF
y   = (((byte1 & 0x3F) << 4) | (byte2 >> 4)) & 0x3FF
dir = byte2 & 0x0F
```
(10-bit range each for x/y, 4-bit direction — matches RO's known map-coordinate ceiling and 8/16-way facing enum.)

### 5.6 `CZ_NOTIFY_ACTORINIT` (LoadEndAck) and `CZ_REQUEST_MOVE` (WalkToXY) — opcodes resolved, sending them is still deferred

Resolved with the same rigor as `CZ_ENTER`, by mechanically extracting every `packet(...)`/`parseable_packet(...)` registration from `clif_packetdb.hpp` (1,232 raw entries, all unconditionally active for our PACKETVER — confirmed there are **zero** `#else` branches in that file, only monotonic `#if PACKETVER >= X` guards, so processing every call in file order and keeping the last write per opcode is safe) merged with `clif_shuffle.hpp`'s applicable `#elif PACKETVER > 20180307` block layered on top (825 unique opcodes total after merge, case-normalized — an earlier pass of this extraction had a bug treating `0x035F` and `0x035f` as different opcodes; fixed by normalizing to decimal before dedup):

* **`CZ_NOTIFY_ACTORINIT`** ("LoadEndAck", `clif_parse_LoadEndAck`, `clif.cpp:11182`) — opcode **`0x007D`**, 2 bytes (opcode only, no payload). Registered unconditionally at the very top of `clif_packetdb.hpp` (`clif_packetdb.hpp:32`, before any `#if` guard) and never touched by `clif_shuffle.hpp` — stable across every PACKETVER, including pre-2018 shuffled clients.
* **`CZ_REQUEST_MOVE`** ("WalkToXY", `clif_parse_WalkToXY`) — opcode **`0x035F`**, 5 bytes: `int16 packetType; uint8 posData[3]` — the destination packed with the same `WBUFPOS` scheme as §5.5, with `dir` unused (movement direction is derived server-side from the path, not sent by the client).
* **Server's own-movement confirmation, `ZC_NOTIFY_PLAYERMOVE`** (`clif_walkok`, `clif.cpp:2366`) — opcode **`0x0087`** (fixed in `packets.hpp` via `DEFINE_PACKET_HEADER`, not part of the shuffled/obfuscated `packet_db` system at all — a reminder that `packet_db` only covers packets the server *parses from the client*, not most of what it *sends*), 12 bytes: `int16 packetType; uint32 moveStartTime; uint8 moveData[6]`. `moveData` is `WBUFPOS2`-packed (`clif.cpp:186`, a 6-byte variant of §5.5's scheme encoding *two* positions): `byte0=x0>>2; byte1=(x0<<6)|((y0>>4)&0x3F); byte2=(y0<<4)|((x1>>6)&0x0F); byte3=(x1<<2)|((y1>>8)&0x03); byte4=y1; byte5=(sx0<<4)|(sy0&0x0F)`, decoded as `x0=(byte0<<2)|(byte1>>6); y0=((byte1&0x3F)<<4)|(byte2>>4); x1=((byte2&0x0F)<<6)|(byte3>>2); y1=((byte3&0x03)<<8)|byte4` — `(x0,y0)` is the walk's start tile, `(x1,y1)` its destination.

**Why none of this is sent yet, despite being fully resolved**: `LoadEndAck` is what actually triggers `clif_spawn()`, `clif_inventorylist()`, and `map_foreachinallarea(clif_getareachar, ...)` for every nearby entity (`clif_parse_LoadEndAck`, `clif.cpp:11182`) — sending it opens the floodgate to the *entire rest* of the gameplay protocol (inventory, stats, skills, every nearby player/monster/NPC spawn packet) landing on this socket. The `packet_db` extraction above only covers **client→server** packets (what the server validates lengths for on the way in) — it does not help decode the dozens of distinct **server→client** packet shapes that would start arriving, since those are defined as ad-hoc structs throughout a ~27,000-line file with no equivalent central length registry. Fully and safely handling that (even just enough to skip-and-not-crash on packets this client doesn't act on) is its own substantial reverse-engineering effort, not a natural extension of what's been verified so far. Sending `WalkToXY` is gated on the same problem, since movement almost certainly requires the character to already be in the server's "spawned/active" state that only `LoadEndAck` triggers. **Decision for this pass**: implement real local movement against the real map/walkability data (§2 of `FATE_MMO_MOBILE_ASSETS.md`) without sending either packet yet, clearly presented as client-local exploration rather than server-synced movement — see `FATE_MMO_MOBILE_ROADMAP.md` Phase 3 for the honest split.

## 6. What this means for the Android client roadmap

* **Phase 1 (network PoC)** only needs §3: connect to login, send `CA_LOGIN`, parse `AC_ACCEPT_LOGIN`/`AC_REFUSE_LOGIN`. Fully specified above, byte-for-byte, from source.
* **Phase 2 (char select)** needs §4 — now fully byte-mapped: char-list auto-push (082D/006B/09A0/020D), select, create, and delete are all specified above with exact opcodes/offsets for PACKETVER 20250716, including two version-gated surprises a generic rAthena guide would get wrong for this build: `HC_ACCEPT_MAKECHAR` is `0x0B6F` (not the classic `0x006D`), and character deletion needs the account's **birthdate**, not an email, per this server's shipped `char_del_option: 2`.
* **Phase 3 (map connect)** needs §5 — now fully resolved: the Phase 1/2 placeholder opcode (`0x0072`) was confirmed wrong by actually tracing the runtime packet table (it's reassigned to a skill-use packet for modern clients); the real handshake is `CZ_ENTER` at `0x0436` (23 bytes) → session echo (`0x0283`) → an invisible char-server round trip → `ZC_ACCEPT_ENTER` (`0x02EB`) or `ZC_REFUSE_ENTER` (`0x0074`). Also resolved: this PACKETVER has both packet-ID shuffling and XOR obfuscation mechanisms present in the source but functionally inert (zero-valued keys, no shuffle table entry past 2018-03-07) — traced precisely, not assumed either way.
* Full gameplay packets (movement after connect, entity spawn, NPC dialogue, item pickup, inventory, skills, combat, chat, party, guild, storage, trade) remain uncatalogued, explicitly deferred to whichever later phase first needs each one (§5.6).

## 7. Production networking caveat (protocol, not client, problem)

Per §3.1/§5.0, rAthena does not meaningfully encrypt any of these three sockets **for this specific server build**: login/char never did, and the map server's packet-obfuscation mechanism resolves to an identity no-op for PACKETVER 20250716 (zero-valued keys, confirmed by tracing `clif_cryptKey`'s actual constants rather than assumed from the `#ifdef PACKET_OBFUSCATION` guard alone). That's a property of the server protocol as currently built, not something to patch around in the Android client with a home-rolled cipher (which would just be security theater and would desync from the PC client's expectations) — and also not something to silently rely on forever, since a server rebuild for an older PACKETVER would reactivate a real cipher this client doesn't implement. The correct fix, if internet-facing security matters here, is a TLS-terminating reverse proxy / stunnel in front of ports 6900/char/map, transparent to both PC and mobile clients — a deployment change, not a client or rAthena source change. Flagged here so it isn't silently "fixed" in the wrong layer later.
