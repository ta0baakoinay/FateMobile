package com.fatemmo.mobile.net

/**
 * Byte-level layout for the map-server connect handshake, transcribed from
 * the FateRO server source (src/map/clif.cpp, src/map/packets.hpp,
 * src/map/clif_packetdb.hpp + clif_shuffle.hpp, PACKETVER 20250716).
 * See docs/FATE_MMO_MOBILE_PROTOCOL.md §5 for the full derivation.
 *
 * Two things this doc had to actually verify rather than assume, both
 * documented in the protocol doc §5.0-§5.1:
 *  - `CZ_ENTER` is opcode 0x0436 for this build, NOT 0x0072 (which the
 *    server's own map/clif.cpp reassigns to a skill-use packet for modern
 *    clients — a generic/stale rAthena reference would get this wrong).
 *  - The map server's packet-ID obfuscation/shuffle mechanisms are present
 *    in the source but resolve to a no-op for this exact PACKETVER (zero
 *    keys, no shuffle table entry past 2018-03-07) — confirmed by tracing
 *    the actual constants, so no cipher is implemented here.
 */
object MapOpcodes {
    const val CZ_ENTER: Int = 0x0436
    const val ZC_SESSION_ECHO: Int = 0x0283
    const val ZC_ACCEPT_ENTER: Int = 0x02EB
    const val ZC_REFUSE_ENTER: Int = 0x0074
    const val SC_NOTIFY_BAN: Int = 0x0081 // map-server context — see protocol doc §5.4

    /**
     * Not in the original protocol doc — found only by live-testing against
     * the real server (2026-09-14), which sends this between the session
     * echo and ZC_ACCEPT_ENTER. Reports inventory-slot expansion; harmless
     * to skip for Phase 3's purposes. See docs/FATE_MMO_MOBILE_PROTOCOL.md §5.4.
     */
    const val ZC_EXTEND_BODYITEM_SIZE: Int = 0x0B18
}

object MapPacketSizes {
    /** packetType(2) + account_id(4) + char_id(4) + login_id1(4) + client_tick(4) + unknown(4) + sex(1) */
    const val CZ_ENTER = 2 + 4 + 4 + 4 + 4 + 4 + 1 // 23 bytes

    /** packetType(2) already consumed; sessionId(4) */
    const val ZC_SESSION_ECHO_BODY = 4

    /** packetType(2) already consumed; startTime(4) + posDir(3) + xSize(1) + ySize(1) + font(2) */
    const val ZC_ACCEPT_ENTER_BODY = 4 + 3 + 1 + 1 + 2 // 11 bytes (13 total with opcode)

    /** packetType(2) already consumed; expansionSize(2) */
    const val ZC_EXTEND_BODYITEM_SIZE_BODY = 2
}

sealed class MapEnterResult {
    data class Success(val startTime: Long, val x: Int, val y: Int, val dir: Int) : MapEnterResult()
    data class Refused(val errorCode: Int) : MapEnterResult()
    data class Banned(val errorCode: Int) : MapEnterResult()
    data class ConnectionError(val message: String) : MapEnterResult()
}
