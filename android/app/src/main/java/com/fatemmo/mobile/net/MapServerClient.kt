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

/**
 * Phase 3 map-server client (docs/FATE_MMO_MOBILE_ROADMAP.md, Phase 3).
 *
 * Performs the `CZ_ENTER` handshake documented in
 * docs/FATE_MMO_MOBILE_PROTOCOL.md §5 and reports back the server-confirmed
 * spawn position. **Deliberately stops there** — it does not send
 * `CZ_NOTIFY_ACTORINIT` ("LoadEndAck"), which is what would make the server
 * start streaming inventory/nearby-entity/gameplay packets this client has
 * no renderer or entity system to handle yet (protocol doc §5.6). Actually
 * entering the world is later-phase work, once there's something to draw.
 *
 * Still plain Kotlin/java.net.Socket, same rationale as LoginClient/
 * CharServerClient — see docs/FATE_MMO_MOBILE_ARCHITECTURE.md §2.
 */
class MapServerClient {

    companion object {
        private const val TAG = "FateMMO/MapServerClient"
        private const val CONNECT_TIMEOUT_MS = 8_000

        // The real answer only arrives after an invisible map-server <-> char-server
        // round trip (protocol doc §5.3) with no intermediate "please wait" packet,
        // so this is longer than the login/char read timeouts, not just copy-pasted.
        private const val READ_TIMEOUT_MS = 15_000
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    suspend fun connect(
        host: String,
        port: Int,
        accountId: Long,
        charId: Long,
        loginId1: Long,
        sex: Int
    ): MapEnterResult = withContext(Dispatchers.IO) {
        try {
            val s = Socket()
            s.soTimeout = READ_TIMEOUT_MS
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket = s
            output = DataOutputStream(s.getOutputStream())
            input = DataInputStream(s.getInputStream())

            writePacket(buildCzEnter(accountId, charId, loginId1, sex))

            readEnterSequence()
        } catch (e: Exception) {
            Log.w(TAG, "Map-server connect failed: ${e.message}", e)
            closeQuietly()
            MapEnterResult.ConnectionError(e.message ?: e.javaClass.simpleName)
        }
    }

    fun close() {
        closeQuietly()
    }

    private fun readEnterSequence(): MapEnterResult {
        val input = this.input!!
        while (true) {
            val opcode = readUInt16LE(input)
            when (opcode) {
                MapOpcodes.ZC_SESSION_ECHO -> {
                    // Session id echo, not the account id — see protocol doc §5.2.
                    // Consume and keep waiting; the real answer comes after an
                    // invisible char-server round trip (protocol doc §5.3).
                    input.readFully(ByteArray(MapPacketSizes.ZC_SESSION_ECHO_BODY))
                }

                MapOpcodes.ZC_ACCEPT_ENTER -> {
                    val body = ByteArray(MapPacketSizes.ZC_ACCEPT_ENTER_BODY)
                    input.readFully(body)
                    val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                    val startTime = buf.int.toUInt32()
                    val posDir = ByteArray(3).also { buf.get(it) }
                    // xSize/ySize/font follow but aren't needed for Phase 3's debug display.
                    val (x, y, dir) = decodePosDir(posDir)
                    return MapEnterResult.Success(startTime, x, y, dir)
                }

                MapOpcodes.ZC_REFUSE_ENTER -> return MapEnterResult.Refused(input.readUnsignedByte())
                MapOpcodes.SC_NOTIFY_BAN -> return MapEnterResult.Banned(input.readUnsignedByte())

                // ZC_EXTEND_BODYITEM_SIZE — NOT in the original protocol doc; found only by
                // live-testing against the real server, which sends it between the session
                // echo and ZC_ACCEPT_ENTER. Confirms the doc's own warning that this
                // handshake window can carry more packets than initially catalogued.
                // See docs/FATE_MMO_MOBILE_PROTOCOL.md §5.4.
                MapOpcodes.ZC_EXTEND_BODYITEM_SIZE -> input.readFully(ByteArray(MapPacketSizes.ZC_EXTEND_BODYITEM_SIZE_BODY))

                else -> return MapEnterResult.ConnectionError("Unexpected opcode 0x${opcode.toString(16)} while awaiting map entry")
            }
        }
    }

    /** Reverses the WBUFPOS bit-packing from clif.cpp:177 — see protocol doc §5.5. */
    private fun decodePosDir(posDir: ByteArray): Triple<Int, Int, Int> {
        val b0 = posDir[0].toInt() and 0xFF
        val b1 = posDir[1].toInt() and 0xFF
        val b2 = posDir[2].toInt() and 0xFF
        val x = ((b0 shl 2) or (b1 shr 6)) and 0x3FF
        val y = (((b1 and 0x3F) shl 4) or (b2 shr 4)) and 0x3FF
        val dir = b2 and 0x0F
        return Triple(x, y, dir)
    }

    private fun buildCzEnter(accountId: Long, charId: Long, loginId1: Long, sex: Int): ByteArray {
        val buf = ByteBuffer.allocate(MapPacketSizes.CZ_ENTER).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(MapOpcodes.CZ_ENTER.toShort())
        buf.putInt(accountId.toInt())
        buf.putInt(charId.toInt())
        buf.putInt(loginId1.toInt())
        buf.putInt((System.currentTimeMillis() and 0xFFFFFFFFL).toInt()) // client_tick, not validated server-side
        buf.putInt(0) // unknown 4-byte gap, not read by packet_db[0x0436].pos[] — see protocol doc §5.1
        buf.put(sex.toByte())
        return buf.array()
    }

    private fun writePacket(bytes: ByteArray) {
        output!!.write(bytes)
        output!!.flush()
    }

    private fun readUInt16LE(input: DataInputStream): Int {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        return (b1 shl 8) or b0
    }

    private fun Int.toUInt32(): Long = this.toLong() and 0xFFFFFFFFL

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
