package com.blackghost56.alink

import com.blackghost56.alink.tools.COBS
import com.blackghost56.alink.tools.CobsFrameReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CobsFrameReaderTest {
    @Test
    fun resumesAtDelimiterAndPreservesSplitFrameAtLimit() {
        val payload = byteArrayOf(0, 0, 0, 0, 7)
        val frame = COBS.encode(payload.asList()).toByteArray()
        val reader = CobsFrameReader(frame.size - 1)
        val received = mutableListOf<ByteArray>()
        reader.accept(byteArrayOf(0, 4, 1, 0, 1, 0) + ByteArray(20) { 1 }) { received.add(it) }
        reader.accept(byteArrayOf(1, 0) + frame.copyOfRange(0, 2)) { received.add(it) }
        reader.accept(frame.copyOfRange(2, frame.size) + frame) { received.add(it) }
        assertEquals(2, received.size)
        received.forEach { assertArrayEquals(payload, it) }
    }
}
