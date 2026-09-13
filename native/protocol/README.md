# native/protocol

Packet struct definitions + encode/decode for login/char/map, mirroring
docs/FATE_MMO_MOBILE_PROTOCOL.md exactly. This is the only place packet byte
layouts are allowed to live once networking migrates out of Kotlin (Phase 3+).

**Status:** empty — Phase 1's login packet codec lives in
android/app/.../net/LoginPackets.kt for now.
