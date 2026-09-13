# native/networking

TCP socket lifecycle, thread-safe send/receive queues, main-thread-safe event
dispatch. See docs/FATE_MMO_MOBILE_ARCHITECTURE.md §3.

**Status:** empty. Phase 1's login handshake lives in Kotlin
(android/app/.../net/LoginClient.kt) by design — this module gets populated
starting Phase 3 (map connection) per docs/FATE_MMO_MOBILE_ROADMAP.md, once
packet volume/frequency justifies moving off the JNI boundary.
