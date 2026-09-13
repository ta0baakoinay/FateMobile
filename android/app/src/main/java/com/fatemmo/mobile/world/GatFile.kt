package com.fatemmo.mobile.world

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser for Gravity's .gat ground/walkability format — same format, same
 * verified layout as tools/grf/src/GatFile.java (see
 * docs/FATE_MMO_MOBILE_ASSETS.md §2): header fields plus width*height*20
 * -byte cell records account for the real prontera.gat's exact file size,
 * confirming this layout rather than assuming it from memory.
 */
class GatFile private constructor(
    val width: Int,
    val height: Int,
    private val type: IntArray
) {
    /** Common convention: type 0 (walkable ground) and 3 (walkable water) are passable. */
    fun isWalkable(x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || x >= width || y >= height) return false
        val t = type[y * width + x]
        return t == 0 || t == 3
    }

    companion object {
        fun parse(data: ByteArray): GatFile {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val sig = ByteArray(4).also { buf.get(it) }
            require(sig[0] == 'G'.code.toByte() && sig[1] == 'R'.code.toByte() && sig[2] == 'A'.code.toByte() && sig[3] == 'T'.code.toByte()) {
                "Not a GAT file (bad signature)"
            }
            buf.get() // major
            buf.get() // minor
            val width = buf.int
            val height = buf.int

            val type = IntArray(width * height)
            for (i in 0 until width * height) {
                buf.float; buf.float; buf.float; buf.float // 4 corner heights, unused for walkability
                type[i] = buf.int
            }
            return GatFile(width, height, type)
        }
    }
}
