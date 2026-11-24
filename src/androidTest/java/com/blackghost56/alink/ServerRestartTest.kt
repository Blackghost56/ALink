package com.blackghost56.alink

import androidx.test.platform.app.InstrumentationRegistry
import com.blackghost56.alink.server.ALinkTCPServer
import com.blackghost56.alink.client.ALinkTCPClient
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
    fun reconnectsToLoopbackListenerOnItsAssignedPort() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val events = LinkedBlockingQueue<String>()
        val server = ALinkTCPServer(context, "unused", 0, object : ALinkTCPServer.Callback {
            override fun onSuccessStart() { events.add("ready") }
            override fun onError(msg: String) { events.add(msg) }
            override fun onNewConnection(address: Int) {}
            override fun onDisconnect(address: Int) {}
            override fun onDataRx(address: Int, data: ByteArray) { events.add(data.decodeToString()) }
        }, localOnly = true)
        val client = ALinkTCPClient(context, "unused", object : ALinkTCPClient.Callback {
            override fun onSocketOpened() { events.add("connected") }
            override fun onSocketClosed() { events.add("closed") }
            override fun onDataRx(data: ByteArray) {}
        })
        try {
            repeat(2) {
                server.start()
                assertEquals("ready", events.poll(5, TimeUnit.SECONDS))
                client.connectLocal(requireNotNull(server.listeningPort))
                assertEquals("connected", events.poll(5, TimeUnit.SECONDS))
                client.send("sample".encodeToByteArray())
                assertEquals("sample", events.poll(5, TimeUnit.SECONDS))
                server.stop()
                assertEquals("closed", events.poll(5, TimeUnit.SECONDS))
                assertEquals(null, server.listeningPort)
            }
        } finally {
            client.stop()
            server.stop()
        }
    }

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
