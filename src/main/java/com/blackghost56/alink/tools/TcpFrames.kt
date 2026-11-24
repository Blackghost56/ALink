package com.blackghost56.alink.tools

import java.net.ProtocolException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Without COBS the little-endian length covers the ALink header and payload. */
internal class TcpFrames(cobsEnabled: Boolean) {
    private val cobs = if (cobsEnabled) CobsFrameReader() else null
    private val prefix = ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private var message: ByteBuffer? = null

    fun accept(data: ByteArray, count: Int, onFrame: (ByteArray) -> Unit) {
        if (cobs != null) {
            cobs.accept(data, count, onFrame)
            return
        }
        var offset = 0
        while (offset < count) {
            val target = message ?: prefix
            val length = minOf(target.remaining(), count - offset)
            target.put(data, offset, length)
            offset += length
            if (target.hasRemaining()) continue
            if (target === prefix) {
                val size = prefix.getInt(0)
                prefix.clear()
                if (size !in Int.SIZE_BYTES..MAX_SIZE) throw ProtocolException("Invalid ALink message size: $size")
                message = ByteBuffer.allocate(size)
            } else {
                message = null
                onFrame(target.array())
            }
        }
    }

    companion object {
        private const val MAX_SIZE = 1024 * 1024

        fun encode(cobsEnabled: Boolean, raw: ByteArray): ByteArray {
            if (cobsEnabled) return COBS.encode(raw.asList()).toByteArray()
            if (raw.size !in Int.SIZE_BYTES..MAX_SIZE) throw ProtocolException("Invalid ALink message size")
            return ByteBuffer.allocate(Int.SIZE_BYTES + raw.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(raw.size).put(raw).array()
        }
    }
}
