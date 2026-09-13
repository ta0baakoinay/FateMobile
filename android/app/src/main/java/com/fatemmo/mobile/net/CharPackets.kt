package com.fatemmo.mobile.net

/**
 * Byte-level layout for the char-server handshake, character list, select,
 * create, and delete flows — transcribed field-for-field from the FateRO
 * server source (src/char/char_clif.cpp, src/char/packets.hpp, PACKETVER
 * 20250716). See docs/FATE_MMO_MOBILE_PROTOCOL.md §4 for the full
 * derivation, including two version-gated opcodes a generic rAthena guide
 * would get wrong for this build (HC_ACCEPT_MAKECHAR = 0x0B6F, not 0x006D)
 * and a config-gated field (char deletion wants a birthdate here, not an
 * email, per this server's shipped char_del_option: 2).
 */
object CharOpcodes {
    const val CH_ENTER: Int = 0x0065
    const val HC_REFUSE_ENTER: Int = 0x006C
    const val HC_AUTH_RESULT: Int = 0x0081 // distinct meaning from login-server's SC_NOTIFY_BAN, same wire opcode
    const val HC_SLOT_SUMMARY: Int = 0x082D
    const val HC_ACCEPT_ENTER: Int = 0x006B
    const val HC_CHARLIST_NOTIFY: Int = 0x09A0
    const val HC_BLOCK_CHARACTER: Int = 0x020D
    const val HC_ACK_PINCODE: Int = 0x08B9 // inert unless the server enables pincode_enabled

    const val CH_SELECT_CHAR: Int = 0x0066
    const val HC_NOTIFY_ZONESVR: Int = 0x0AC5
    const val HC_NOTIFY_ACCESSIBLE_MAPNAME: Int = 0x0840

    const val CH_MAKE_CHAR: Int = 0x0A39
    const val HC_REFUSE_MAKECHAR: Int = 0x006E
    const val HC_ACCEPT_MAKECHAR: Int = 0x0B6F

    const val CH_DELETE_CHAR: Int = 0x0068
    const val HC_REFUSE_DELETECHAR: Int = 0x0070
    const val HC_ACCEPT_DELETECHAR: Int = 0x006F
}

object CharPacketSizes {
    const val NAME_LENGTH = 24

    /** packetType(2) + account_id(4) + login_id1(4) + login_id2(4) + sex(1) */
    const val CH_ENTER = 2 + 4 + 4 + 4 + 1 // 17 bytes

    /** Bare, opcode-less account_id echo the server sends right after CH_ENTER. */
    const val CH_ENTER_ECHO = 4

    const val HC_SLOT_SUMMARY_BODY = 29 - 4 // header(4) already consumed by generic reader
    const val CHARACTER_INFO = 175 // see docs/FATE_MMO_MOBILE_PROTOCOL.md §4.2 for the full offset table
    const val HC_ACCEPT_ENTER_FIXED_HEADER = 27 - 4 // bytes after packetType+packetLength, before character entries

    const val CH_SELECT_CHAR = 2 + 1 // 3 bytes

    /** packetType(2) already consumed; char_id(4) + mapName(16) + ip(4) + port(2) + unknown(128) */
    const val HC_NOTIFY_ZONESVR_BODY = 4 + 16 + 4 + 2 + 128 // 154 bytes (packet total 156)

    const val CH_MAKE_CHAR = 2 + NAME_LENGTH + 1 + 2 + 2 + 2 + 2 + 1 // 36 bytes
    const val HC_ACCEPT_MAKECHAR_BODY = CHARACTER_INFO // packetType(2) already consumed

    /** packetType(2) + char_id(4) + confirmation code (birthdate/email)(40) */
    const val CH_DELETE_CHAR = 2 + 4 + 40 // 46 bytes
    const val CH_DELETE_CHAR_CONFIRM_FIELD = 40
}

data class CharacterInfo(
    val charId: Long,
    val slot: Int,
    val name: String,
    val job: Int,
    val level: Int,
    val jobLevel: Int,
    val sex: Int,
    val mapName: String
)

/** Result of the CH_ENTER handshake + the four-packet char-list auto-push. */
sealed class CharListResult {
    data class Success(
        val producibleSlots: Int,
        val maxSlots: Int,
        val characters: List<CharacterInfo>
    ) : CharListResult()

    data class Refused(val errorCode: Int) : CharListResult()

    data class ConnectionError(val message: String) : CharListResult()
}

sealed class CharSelectResult {
    data class MapRedirect(val charId: Long, val mapName: String, val mapIp: String, val mapPort: Int) : CharSelectResult()
    data class Refused(val errorCode: Int) : CharSelectResult()
    data class NoMapServerAvailable(val accessibleMaps: List<String>) : CharSelectResult()
    data class ConnectionError(val message: String) : CharSelectResult()
}

sealed class CharCreateResult {
    data class Success(val character: CharacterInfo) : CharCreateResult()
    data class Refused(val errorCode: Int) : CharCreateResult()
    data class ConnectionError(val message: String) : CharCreateResult()
}

sealed class CharDeleteResult {
    data object Success : CharDeleteResult()
    data class Refused(val errorCode: Int) : CharDeleteResult()
    data class ConnectionError(val message: String) : CharDeleteResult()
}
