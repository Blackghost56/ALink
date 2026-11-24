package com.blackghost56.alink

import com.blackghost56.alink.tools.TcpFrames
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ProtocolException

class TcpFramesTest {
    @Test
    fun readsSplitPrefixPayloadAndMultipleFramesOnlyUpToCount() {
        val raw = byteArrayOf(0, 0, 0, 0, 11)
        val wire = TcpFrames.encode(false, raw)
        assertEquals(listOf<Byte>(5, 0, 0, 0), wire.take(4))
        val stream = wire + wire
        for (split in 0..stream.size) {
            val reader = TcpFrames(false)
            val frames = mutableListOf<List<Byte>>()
            reader.accept(stream, split) { frames.add(it.toList()) }
            reader.accept(stream.copyOfRange(split, stream.size), stream.size - split) { frames.add(it.toList()) }
            assertEquals(listOf(raw.toList(), raw.toList()), frames)
        }
    }

    @Test(expected = ProtocolException::class)
    fun rejectsOversizedLengthBeforeAllocating() {
        TcpFrames(false).accept(byteArrayOf(1, 0, 16, 0), 4) {}
    }
}
