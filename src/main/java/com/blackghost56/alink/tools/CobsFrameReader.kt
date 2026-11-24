package com.blackghost56.alink.tools

import java.io.ByteArrayOutputStream

internal class CobsFrameReader(private val maxEncodedSize: Int = 1024 * 1024) {
    private val partial = ByteArrayOutputStream()
    private var discarding = false

    fun accept(data: ByteArray, onFrame: (ByteArray) -> Unit) {
        for (value in data) {
            if (value == COBS.EOP) {
                discarding = false
                if (partial.size() == 0) continue
                val encoded = partial.toByteArray()
                partial.reset()
                val decoded = try {
                    COBS.decode(encoded.asList())
                } catch (_: IllegalArgumentException) {
                    continue
                }
                if (decoded.size >= Int.SIZE_BYTES) onFrame(decoded.toByteArray())
            } else if (!discarding) {
                if (partial.size() == maxEncodedSize) {
                    partial.reset()
                    discarding = true
                } else {
                    partial.write(value.toInt())
                }
            }
        }
    }
}
