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
 * Phase 2 char-server client (docs/FATE_MMO_MOBILE_ROADMAP.md, Phase 2).
 *
 * Speaks the char-server handshake, char-list auto-push, select/create/delete
 * flows documented in docs/FATE_MMO_MOBILE_PROTOCOL.md §4, verified against
 * the FateRO server source. Unlike LoginClient (one request, one response,
 * connection closed), this class holds a **single persistent socket** across
 * [connect], [selectCharacter], [createCharacter], and [deleteCharacter] —
 * the char-server keeps state per-connection, and closing/reopening between
 * calls would just make it re-run the CH_ENTER handshake it already did.
 *
 * Still deliberately plain Kotlin/java.net.Socket rather than the native
 * (C++) networking layer — see docs/FATE_MMO_MOBILE_ARCHITECTURE.md §2 for
 * why that migration happens starting Phase 3, not here.
 */
class CharServerClient {

    companion object {
        private const val TAG = "FateMMO/CharServerClient"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 10_000
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    suspend fun connect(
        host: String,
        port: Int,
        accountId: Long,
        loginId1: Long,
        loginId2: Long,
        sex: Int
    ): CharListResult = withContext(Dispatchers.IO) {
        try {
            val s = Socket()
            s.soTimeout = READ_TIMEOUT_MS
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket = s
            output = DataOutputStream(s.getOutputStream())
            input = DataInputStream(s.getInputStream())

            writePacket(buildChEnter(accountId, loginId1, loginId2, sex))

            // CH_ENTER's reply is a bare 4-byte account_id echo — no opcode prefix.
            // Skipping this desyncs every read that follows. See protocol doc §4.1.
            val echo = ByteArray(CharPacketSizes.CH_ENTER_ECHO)
            input!!.readFully(echo)

            readCharListSequence()
        } catch (e: Exception) {
            Log.w(TAG, "Char-server connect failed: ${e.message}", e)
            closeQuietly()
            CharListResult.ConnectionError(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun selectCharacter(slot: Int): CharSelectResult = withContext(Dispatchers.IO) {
        val out = output
        val input = this@CharServerClient.input
        if (out == null || input == null) {
            return@withContext CharSelectResult.ConnectionError("Not connected")
        }
        try {
            val buf = ByteBuffer.allocate(CharPacketSizes.CH_SELECT_CHAR).order(ByteOrder.LITTLE_ENDIAN)
            buf.putShort(CharOpcodes.CH_SELECT_CHAR.toShort())
            buf.put(slot.toByte())
            writePacket(buf.array())

            val opcode = readUInt16LE(input)
            when (opcode) {
                CharOpcodes.HC_NOTIFY_ZONESVR -> {
                    val body = ByteArray(CharPacketSizes.HC_NOTIFY_ZONESVR_BODY)
                    input.readFully(body)
                    val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                    val charId = b.int.toUInt32()
                    val mapNameBytes = ByteArray(16).also { b.get(it) }
                    val ipBytes = ByteArray(4).also { b.get(it) }
                    val ip = "${ipBytes[0].toUByteInt()}.${ipBytes[1].toUByteInt()}.${ipBytes[2].toUByteInt()}.${ipBytes[3].toUByteInt()}"
                    val mapPort = b.short.toInt() and 0xFFFF
                    // remaining 128 bytes unknown/zero-filled, not consumed further — end of packet.
                    CharSelectResult.MapRedirect(charId, cString(mapNameBytes), ip, mapPort)
                }
                CharOpcodes.HC_REFUSE_ENTER -> {
                    val err = input.readUnsignedByte()
                    CharSelectResult.Refused(err)
                }
                CharOpcodes.HC_NOTIFY_ACCESSIBLE_MAPNAME -> {
                    val packetLength = readUInt16LE(input)
                    val body = ByteArray(packetLength - 4)
                    input.readFully(body)
                    val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                    val maps = mutableListOf<String>()
                    while (b.remaining() >= 4 + 16) {
                        b.int // status, not surfaced in Phase 2
                        val mapBytes = ByteArray(16).also { b.get(it) }
                        maps += cString(mapBytes)
                    }
                    CharSelectResult.NoMapServerAvailable(maps)
                }
                else -> CharSelectResult.ConnectionError("Unexpected opcode 0x${opcode.toString(16)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "selectCharacter failed: ${e.message}", e)
            CharSelectResult.ConnectionError(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun createCharacter(
        name: String,
        slot: Int,
        hairColor: Int,
        hairStyle: Int,
        startJob: Int,
        sex: Int
    ): CharCreateResult = withContext(Dispatchers.IO) {
        val input = this@CharServerClient.input
        if (output == null || input == null) {
            return@withContext CharCreateResult.ConnectionError("Not connected")
        }
        try {
            val buf = ByteBuffer.allocate(CharPacketSizes.CH_MAKE_CHAR).order(ByteOrder.LITTLE_ENDIAN)
            buf.putShort(CharOpcodes.CH_MAKE_CHAR.toShort())
            buf.put(fixedField(name, CharPacketSizes.NAME_LENGTH))
            buf.put(slot.toByte())
            buf.putShort(hairColor.toShort())
            buf.putShort(hairStyle.toShort())
            buf.putShort(startJob.toShort())
            buf.putShort(0) // unknown, server ignores this field
            buf.put(sex.toByte())
            writePacket(buf.array())

            val opcode = readUInt16LE(input)
            when (opcode) {
                CharOpcodes.HC_ACCEPT_MAKECHAR -> {
                    val body = ByteArray(CharPacketSizes.HC_ACCEPT_MAKECHAR_BODY)
                    input.readFully(body)
                    CharCreateResult.Success(parseCharacterInfo(ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)))
                }
                CharOpcodes.HC_REFUSE_MAKECHAR -> {
                    val err = input.readUnsignedByte()
                    CharCreateResult.Refused(err)
                }
                else -> CharCreateResult.ConnectionError("Unexpected opcode 0x${opcode.toString(16)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "createCharacter failed: ${e.message}", e)
            CharCreateResult.ConnectionError(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * @param birthdateOrEmail confirmation code this server expects in the 40-byte
     * field — per docs/FATE_MMO_MOBILE_PROTOCOL.md §4.6, THIS server's shipped
     * conf (char_del_option: 2) wants the account's birthdate as "YYMMDD", not an
     * email address. Verify the live server's actual char_del_option before
     * assuming that still holds.
     */
    suspend fun deleteCharacter(charId: Long, birthdateOrEmail: String): CharDeleteResult = withContext(Dispatchers.IO) {
        val input = this@CharServerClient.input
        if (output == null || input == null) {
            return@withContext CharDeleteResult.ConnectionError("Not connected")
        }
        try {
            val buf = ByteBuffer.allocate(CharPacketSizes.CH_DELETE_CHAR).order(ByteOrder.LITTLE_ENDIAN)
            buf.putShort(CharOpcodes.CH_DELETE_CHAR.toShort())
            buf.putInt(charId.toInt())
            buf.put(fixedField(birthdateOrEmail, CharPacketSizes.CH_DELETE_CHAR_CONFIRM_FIELD))
            writePacket(buf.array())

            val opcode = readUInt16LE(input)
            when (opcode) {
                CharOpcodes.HC_ACCEPT_DELETECHAR -> CharDeleteResult.Success
                CharOpcodes.HC_REFUSE_DELETECHAR -> CharDeleteResult.Refused(input.readUnsignedByte())
                else -> CharDeleteResult.ConnectionError("Unexpected opcode 0x${opcode.toString(16)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "deleteCharacter failed: ${e.message}", e)
            CharDeleteResult.ConnectionError(e.message ?: e.javaClass.simpleName)
        }
    }

    fun close() {
        closeQuietly()
    }

    // ---- char-list auto-push (082D + 006B + 09A0 + 020D), see protocol doc §4.2 ----

    private fun readCharListSequence(): CharListResult {
        val input = this.input!!
        var producibleSlots = 0
        var maxSlots = 0
        var characters: List<CharacterInfo> = emptyList()
        var sawSlotSummary = false
        var sawAcceptEnter = false

        // HC_BLOCK_CHARACTER (020D) is always the last packet of this sequence on a
        // server with pincode disabled (this server's shipped default — see protocol
        // doc §4.2). If an operator later enables pincode_enabled, an extra 0x08B9
        // arrives after this and the next read on this socket (e.g. selectCharacter)
        // will desync; revisit this loop's exit condition if that config ever changes.
        while (true) {
            val opcode = readUInt16LE(input)
            when (opcode) {
                CharOpcodes.HC_REFUSE_ENTER -> return CharListResult.Refused(input.readUnsignedByte())
                CharOpcodes.HC_AUTH_RESULT -> return CharListResult.Refused(input.readUnsignedByte())

                CharOpcodes.HC_SLOT_SUMMARY -> {
                    val packetLength = readUInt16LE(input)
                    val body = ByteArray(packetLength - 4)
                    input.readFully(body)
                    producibleSlots = body[3].toInt() and 0xFF
                    maxSlots = body[4].toInt() and 0xFF
                    sawSlotSummary = true
                }

                CharOpcodes.HC_ACCEPT_ENTER -> {
                    val packetLength = readUInt16LE(input)
                    val body = ByteArray(packetLength - 4)
                    input.readFully(body)
                    characters = parseCharacterList(body)
                    sawAcceptEnter = true
                }

                CharOpcodes.HC_CHARLIST_NOTIFY -> {
                    // Fixed 6-byte packet for this PACKETVER — no length field, just
                    // a totalPageCount int32 we don't need for a single-page render.
                    input.readFully(ByteArray(4))
                }

                CharOpcodes.HC_BLOCK_CHARACTER -> {
                    val packetLength = readUInt16LE(input)
                    input.readFully(ByteArray(packetLength - 4)) // ban entries, ignored in Phase 2
                    // Last packet of the sequence on this server's default config — done.
                    return if (sawSlotSummary && sawAcceptEnter) {
                        CharListResult.Success(producibleSlots, maxSlots, characters)
                    } else {
                        CharListResult.ConnectionError("HC_BLOCK_CHARACTER arrived before HC_SLOT_SUMMARY/HC_ACCEPT_ENTER")
                    }
                }

                CharOpcodes.HC_ACK_PINCODE -> {
                    // Not implemented (pincode disabled by default on this server —
                    // see protocol doc §4.2). Consume it so the stream stays framed,
                    // but don't act on it.
                    input.readFully(ByteArray(10))
                    Log.w(TAG, "Received HC_ACK_PINCODE — server has pincode enabled; not handled by this client yet.")
                }

                else -> return CharListResult.ConnectionError("Unexpected opcode 0x${opcode.toString(16)} while awaiting char list")
            }
        }
    }

    private fun parseCharacterList(body: ByteArray): List<CharacterInfo> {
        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(CharPacketSizes.HC_ACCEPT_ENTER_FIXED_HEADER) // skip MaxSlots/AvailableSlots/PremiumSlots/unknown[20]
        val list = mutableListOf<CharacterInfo>()
        while (buf.remaining() >= CharPacketSizes.CHARACTER_INFO) {
            list += parseCharacterInfo(buf)
        }
        return list
    }

    private fun parseCharacterInfo(buf: ByteBuffer): CharacterInfo {
        val charId = buf.int.toUInt32()
        buf.long // exp
        buf.int // money
        buf.long // jobexp
        val jobLevel = buf.int
        buf.int; buf.int; buf.int; buf.int; buf.int // bodystate/healthstate/effectstate/virtue/honor
        buf.short // jobpoint
        buf.long; buf.long; buf.long; buf.long // hp/maxhp/sp/maxsp
        buf.short // speed
        val job = buf.short.toInt() and 0xFFFF
        buf.short; buf.short; buf.short // head/body/weapon
        val level = buf.short.toInt() and 0xFFFF
        buf.short; buf.short; buf.short; buf.short; buf.short; buf.short; buf.short // sppoint..bodypalette
        val nameBytes = ByteArray(24).also { buf.get(it) }
        buf.get(); buf.get(); buf.get(); buf.get(); buf.get(); buf.get() // Str..Luk
        val slot = buf.get().toInt() and 0xFF
        buf.get() // hairColor
        buf.short // bIsChangedCharName
        val mapNameBytes = ByteArray(16).also { buf.get(it) }
        buf.int; buf.int; buf.int; buf.int // DelRevDate/robePalette/chr_slot_changeCnt/chr_name_changeCnt
        val sex = buf.get().toInt() and 0xFF

        return CharacterInfo(
            charId = charId,
            slot = slot,
            name = cString(nameBytes),
            job = job,
            level = level,
            jobLevel = jobLevel,
            sex = sex,
            mapName = cString(mapNameBytes)
        )
    }

    // ---- shared helpers ----

    private fun buildChEnter(accountId: Long, loginId1: Long, loginId2: Long, sex: Int): ByteArray {
        val buf = ByteBuffer.allocate(CharPacketSizes.CH_ENTER).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(CharOpcodes.CH_ENTER.toShort())
        buf.putInt(accountId.toInt())
        buf.putInt(loginId1.toInt())
        buf.putInt(loginId2.toInt())
        buf.put(sex.toByte())
        return buf.array()
    }

    private fun writePacket(bytes: ByteArray) {
        output!!.write(bytes)
        output!!.flush()
    }

    private fun fixedField(value: String, length: Int): ByteArray {
        val bytes = value.toByteArray(StandardCharsets.US_ASCII)
        val out = ByteArray(length)
        System.arraycopy(bytes, 0, out, 0, minOf(bytes.size, length - 1))
        return out
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

    private fun closeQuietly() {
        try {
            socket?.close()
        } catch (_: Exception) {
            // Already tearing down.
        }
        socket = null
        input = null
        output = null
    }
}
