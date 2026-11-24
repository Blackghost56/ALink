package com.blackghost56.alink

import androidx.test.platform.app.InstrumentationRegistry
import com.blackghost56.alink.server.ALinkTCPServer
import com.blackghost56.alink.tools.COBS
import com.blackghost56.alink.tools.TCPHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ServerRestartTest {
    @Test
    fun retriesOccupiedPortAndAcceptsOrderedMessagesAfterRename() {
        val events = LinkedBlockingQueue<String>()
        val messages = LinkedBlockingQueue<Int>()
        val callback = object : ALinkTCPServer.Callback {
            override fun onSuccessStart() { events.add("started") }
            override fun onError(msg: String) { events.add(msg) }
            override fun onNewConnection(address: Int) {}
            override fun onDisconnect(address: Int) {}
            override fun onDataRx(address: Int, data: ByteArray) { messages.add(data.single().toInt()) }
        }
        ServerSocket(0).use { occupied ->
            val port = occupied.localPort
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "ALink-test-" + System.nanoTime()
            val server = ALinkTCPServer(context, name, port, callback)
            try {
                server.start()
                val failure = events.poll(10, TimeUnit.SECONDS)
                assertTrue("Expected bind error, got $failure", failure?.contains("BindException") == true)
                occupied.close()
                server.start()
                assertEquals("started", events.poll(15, TimeUnit.SECONDS))
                server.name = name + "-new"
                server.restart()
                assertEquals("started", events.poll(15, TimeUnit.SECONDS))
                Socket("127.0.0.1", port).use { client ->
                    repeat(32) { index ->
                        val raw = TCPHeader.toRaw(TCPHeader.TCPMessageType.USER_DATA, byteArrayOf(index.toByte()))
                        client.getOutputStream().write(COBS.encode(raw.asList()).toByteArray())
                    }
                    repeat(32) { index -> assertEquals(index, messages.poll(5, TimeUnit.SECONDS)) }
                }
            } finally {
                server.stop()
            }
        }
    }
}
