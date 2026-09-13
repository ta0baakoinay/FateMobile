package com.fatemmo.mobile.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Phase 1 network proof-of-concept (docs/FATE_MMO_MOBILE_ROADMAP.md, Phase 1).
 *
 * Speaks exactly the login-server handshake documented in
 * docs/FATE_MMO_MOBILE_PROTOCOL.md §3, verified against the FateRO server
 * source. Deliberately plain Kotlin/java.net.Socket rather than the native
 * (C++) networking layer — see docs/FATE_MMO_MOBILE_ARCHITECTURE.md §2 for
 * why that migration happens starting Phase 3, not here.
 *
 * This class does exactly one thing: log in and report the raw result. It
 * does not proceed to char-server or map-server — that's Phase 2/3.
 */
class LoginClient {

    companion object {
        private const val TAG = "FateMMO/LoginClient"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 10_000
    }

    suspend fun login(
        host: String,
        port: Int,
        username: String,
        password: String,
        clientVersion: Long,
        clientType: Int
    ): LoginResult = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.soTimeout = READ_TIMEOUT_MS
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            out.write(buildCaLogin(username, password, clientVersion, clientType))
            out.flush()

            readResponse(input)
        } catch (e: Exception) {
            Log.w(TAG, "Login failed: ${e.message}", e)
            LoginResult.ConnectionError(e.message ?: e.javaClass.simpleName)
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
                // Already tearing down; nothing further to do.
            }
        }
    }

    /** Encodes CA_LOGIN (0x0064) exactly per docs/FATE_MMO_MOBILE_PROTOCOL.md §3.1. */
    private fun buildCaLogin(username: String, password: String, version: Long, clientType: Int): ByteArray {
        val buf = ByteBuffer.allocate(LoginPacketSizes.CA_LOGIN).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(LoginOpcodes.CA_LOGIN.toShort())
        buf.putInt(version.toInt())
        buf.put(fixedField(username, LoginPacketSizes.NAME_LENGTH))
        buf.put(fixedField(password, LoginPacketSizes.NAME_LENGTH))
        buf.put(clientType.toByte())
        return buf.array()
    }

    /** NUL-padded/truncated fixed-width field, matching the server's `char field[N]` layout. */
    private fun fixedField(value: String, length: Int): ByteArray {
        val bytes = value.toByteArray(StandardCharsets.US_ASCII)
        val out = ByteArray(length)
        System.arraycopy(bytes, 0, out, 0, minOf(bytes.size, length - 1))
        return out
    }

    private fun readResponse(input: DataInputStream): LoginResult {
        val opcode = readUInt16LE(input)
        return when (opcode) {
            LoginOpcodes.AC_ACCEPT_LOGIN -> parseAcceptLogin(input)
            LoginOpcodes.AC_REFUSE_LOGIN -> parseRefuseLogin(input)
            LoginOpcodes.SC_NOTIFY_BAN -> parseNotifyBan(input)
            else -> LoginResult.ConnectionError("Unexpected opcode 0x${opcode.toString(16)}")
        }
    }

    private fun parseAcceptLogin(input: DataInputStream): LoginResult {
        val packetLength = readUInt16LE(input)
        val remaining = packetLength - 4 // opcode + length already consumed
        if (remaining < LoginPacketSizes.AC_ACCEPT_LOGIN_HEADER - 4) {
            return LoginResult.ConnectionError("AC_ACCEPT_LOGIN too short: packetLength=$packetLength")
        }
        val body = ByteArray(remaining)
        input.readFully(body)
        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)

        val loginId1 = buf.int.toUInt32()
        val accountId = buf.int.toUInt32()
        val loginId2 = buf.int.toUInt32()
        buf.int // last_ip — not surfaced to the UI in Phase 1.
        val lastLoginBytes = ByteArray(26).also { buf.get(it) }
        val sex = buf.get().toInt() and 0xFF
        val tokenBytes = ByteArray(17).also { buf.get(it) }

        val charServers = mutableListOf<CharServerEntry>()
        while (buf.remaining() >= LoginPacketSizes.AC_ACCEPT_LOGIN_CHAR_SERVER_ENTRY) {
            val ipBytes = ByteArray(4).also { buf.get(it) }
            val ip = "${ipBytes[0].toUByteInt()}.${ipBytes[1].toUByteInt()}.${ipBytes[2].toUByteInt()}.${ipBytes[3].toUByteInt()}"
            val port = buf.short.toInt() and 0xFFFF
            val nameBytes = ByteArray(20).also { buf.get(it) }
            val users = buf.short.toInt() and 0xFFFF
            val type = buf.short.toInt() and 0xFFFF
            val isNew = buf.short.toInt() and 0xFFFF
            buf.position(buf.position() + 128) // unknown[128], reserved

            charServers += CharServerEntry(
                ip = ip,
                port = port,
                name = cString(nameBytes),
                users = users,
                type = type,
                isNew = isNew
            )
        }
        Log.i(TAG, "Login accepted: AID=$accountId lastLogin=${cString(lastLoginBytes)} servers=${charServers.size}")

        return LoginResult.Success(
            accountId = accountId,
            loginId1 = loginId1,
            loginId2 = loginId2,
            sex = sex,
            token = cString(tokenBytes),
            charServers = charServers
        )
    }

    private fun parseRefuseLogin(input: DataInputStream): LoginResult {
        val body = ByteArray(LoginPacketSizes.AC_REFUSE_LOGIN_BODY)
        input.readFully(body)
        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val error = buf.int.toUInt32()
        val unblockBytes = ByteArray(20).also { buf.get(it) }
        return LoginResult.Refused(error, cString(unblockBytes))
    }

    private fun parseNotifyBan(input: DataInputStream): LoginResult {
        val body = ByteArray(LoginPacketSizes.SC_NOTIFY_BAN_BODY)
        input.readFully(body)
        return LoginResult.Banned(body[0].toInt() and 0xFF)
    }

    private fun readUInt16LE(input: DataInputStream): Int {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        return (b1 shl 8) or b0
    }

    private fun Int.toUInt32(): Long = this.toLong() and 0xFFFFFFFFL

    private fun Byte.toUByteInt(): Int = this.toInt() and 0xFF

    private fun cString(bytes: ByteArray): String {
        val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
        return String(bytes, 0, end, StandardCharsets.US_ASCII)
    }
}
