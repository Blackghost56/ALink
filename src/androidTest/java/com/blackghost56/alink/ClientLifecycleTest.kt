package com.blackghost56.alink

import androidx.test.platform.app.InstrumentationRegistry
import com.blackghost56.alink.client.ALinkTCPClient
import com.blackghost56.alink.server.ALinkTCPServer
import com.blackghost56.alink.Consts.Companion.SERVICE_TYPE_TCP
import com.blackghost56.alink.tools.NsdHelper
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ClientLifecycleTest {
    @Test
    fun discoversAgainAfterEofAndKeepsOtherClientDuringDisconnect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "ALink-test-" + System.nanoTime()
        val events = LinkedBlockingQueue<String>()
        val connections = LinkedBlockingQueue<Int>()
        val departures = LinkedBlockingQueue<Int>()
        val port = ServerSocket(0).use { it.localPort }
        val server = ALinkTCPServer(context, name, port, object : ALinkTCPServer.Callback {
            override fun onSuccessStart() { events.add("ready") }
            override fun onError(msg: String) { events.add(msg) }
            override fun onNewConnection(address: Int) { connections.add(address) }
            override fun onDisconnect(address: Int) { departures.add(address) }
            override fun onDataRx(address: Int, data: ByteArray) {}
        }, false)
        val messages = LinkedBlockingQueue<ByteArray>()
        fun client() = ALinkTCPClient(context, name, object : ALinkTCPClient.Callback {
            override fun onSocketOpened() { events.add("opened") }
            override fun onSocketClosed() { events.add("closed") }
            override fun onDataRx(data: ByteArray) { messages.add(data) }
        }, false)
        val first = client()
        val second = client()
        try {
            first.startDiscovery()
            first.stop()
            server.start()
            assertEquals("ready", events.poll(15, TimeUnit.SECONDS))
            first.startDiscovery()
            first.startDiscovery()
            assertEquals("opened", events.poll(20, TimeUnit.SECONDS))
            val old = requireNotNull(connections.poll(5, TimeUnit.SECONDS))
            second.startDiscovery()
            assertEquals("opened", events.poll(20, TimeUnit.SECONDS))
            val other = requireNotNull(connections.poll(5, TimeUnit.SECONDS))
            val payload = ByteArray(24000) { it.toByte() }
            first.stop()
            for (id in server.getClientList()) server.send(id, payload)
            assertArrayEquals(payload, messages.poll(5, TimeUnit.SECONDS))
            assertEquals(old, departures.poll(5, TimeUnit.SECONDS))
            assertEquals(listOf(other), server.getClientList())
            assertEquals("closed", events.poll(5, TimeUnit.SECONDS))
            server.stop()
            assertEquals("closed", events.poll(5, TimeUnit.SECONDS))
            server.start()
            assertEquals("ready", events.poll(15, TimeUnit.SECONDS))
            second.startDiscovery()
            assertEquals("opened", events.poll(20, TimeUnit.SECONDS))
            assertTrue(requireNotNull(connections.poll(5, TimeUnit.SECONDS)) > other)
        } finally {
            first.stop()
            second.stop()
            server.stop()
        }
    }

    @Test
    fun canSearchAfterRefusedConnectionAndAfterIdleTimeout() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "ALink-idle-" + System.nanoTime()
        val port = ServerSocket(0).use { it.localPort }
        val nsd = NsdHelper(context, SERVICE_TYPE_TCP)
        val events = LinkedBlockingQueue<String>()
        val client = ALinkTCPClient(context, name, object : ALinkTCPClient.Callback {
            override fun onSocketOpened() { events.add("open") }
            override fun onSocketClosed() { events.add("close") }
            override fun onDataRx(data: ByteArray) {}
        })
        nsd.registerService(name, port, object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo?) { events.add("ready") }
            override fun onRegistrationFailed(info: NsdServiceInfo?, error: Int) { events.add("error") }
            override fun onServiceUnregistered(info: NsdServiceInfo?) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo?, error: Int) {}
        })
        try {
            assertEquals("ready", events.poll(15, TimeUnit.SECONDS))
            client.startDiscovery()
            assertEquals("close", events.poll(15, TimeUnit.SECONDS))
            ServerSocket(port).use { listener ->
                listener.soTimeout = 15000
                client.startDiscovery()
                listener.accept().use {
                    assertEquals("open", events.poll(5, TimeUnit.SECONDS))
                    assertEquals("close", events.poll(5, TimeUnit.SECONDS))
                }
                client.startDiscovery()
                listener.accept().use { assertEquals("open", events.poll(5, TimeUnit.SECONDS)) }
            }
        } finally {
            client.stop()
            nsd.unregisterService()
        }
    }
}
