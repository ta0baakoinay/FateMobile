package com.fatemmo.mobile.util

/**
 * JNI boundary into native/ (C++). Phase 0/1 only exposes a stub version
 * string to prove the toolchain links; do not add real engine calls here
 * until the corresponding native/ module (see docs/FATE_MMO_MOBILE_ARCHITECTURE.md
 * §2.1) actually implements them.
 */
object NativeBridge {
    init {
        System.loadLibrary("fatemmo_native")
    }

    external fun stubEngineVersion(): String
}
