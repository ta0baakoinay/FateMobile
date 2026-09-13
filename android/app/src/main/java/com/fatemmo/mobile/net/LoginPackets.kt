package com.fatemmo.mobile.net

/**
 * Byte-level layout for the login-server handshake, transcribed field-for-field
 * from the FateRO server source (src/common/packets.hpp, src/login/loginclif.cpp,
 * PACKETVER 20250716). See docs/FATE_MMO_MOBILE_PROTOCOL.md §3 for the full
 * derivation — do not hand-edit sizes/offsets here without re-checking that file
 * against the server source first.
 */
object LoginOpcodes {
    const val CA_LOGIN: Int = 0x0064
    const val AC_ACCEPT_LOGIN: Int = 0x0AC4
    const val AC_REFUSE_LOGIN: Int = 0x083E
    const val SC_NOTIFY_BAN: Int = 0x0081
}

object LoginPacketSizes {
    const val NAME_LENGTH = 24 // rAthena NAME_LENGTH; also used for PASSWD_LENGTH in CA_LOGIN.

    /** packetType(2) + version(4) + username(24) + password(24) + clienttype(1) */
    const val CA_LOGIN = 2 + 4 + NAME_LENGTH + NAME_LENGTH + 1 // 55 bytes

    /**
     * Fixed header of AC_ACCEPT_LOGIN before the variable-length char_servers[]
     * array: packetType(2) + packetLength(2) + login_id1(4) + AID(4) + login_id2(4)
     * + last_ip(4) + last_login(26) + sex(1) + token(17).
     */
    const val AC_ACCEPT_LOGIN_HEADER = 2 + 2 + 4 + 4 + 4 + 4 + 26 + 1 + 17 // 64 bytes

    /** ip(4) + port(2) + name(20) + users(2) + type(2) + new_(2) + unknown(128) */
    const val AC_ACCEPT_LOGIN_CHAR_SERVER_ENTRY = 4 + 2 + 20 + 2 + 2 + 2 + 128 // 160 bytes

    /** packetType(2) already consumed; error(4) + unblock_time(20) */
    const val AC_REFUSE_LOGIN_BODY = 4 + 20 // 24 bytes

    /** packetType(2) already consumed; result(1) */
    const val SC_NOTIFY_BAN_BODY = 1
}

data class CharServerEntry(
    val ip: String,
    val port: Int,
    val name: String,
    val users: Int,
    val type: Int,
    val isNew: Int
)

sealed class LoginResult {
    data class Success(
        val accountId: Long,
        val loginId1: Long,
        val loginId2: Long,
        val sex: Int,
        val token: String,
        val charServers: List<CharServerEntry>
    ) : LoginResult()

    data class Refused(val errorCode: Long, val unblockTime: String) : LoginResult()

    data class Banned(val resultCode: Int) : LoginResult()

    data class ConnectionError(val message: String) : LoginResult()
}
