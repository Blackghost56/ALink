package com.blackghost56.alink.client

import android.content.Context
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.blackghost56.alink.Consts
import com.blackghost56.alink.tools.NsdHelper
import com.blackghost56.alink.tools.TCPHeader
import com.blackghost56.alink.tools.TCPHeader.TCPMessageType.PING
import com.blackghost56.alink.tools.TCPHeader.TCPMessageType.USER_DATA
import com.blackghost56.alink.tools.TcpFrames
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ALinkTCPClient(
    private val context: Context,
    var serverName: String,
    private val callback: Callback,
    val cobsEnable: Boolean = true
) {
    private val tag = ALinkTCPClient::class.java.simpleName
    private var current: Session? = null

    interface Callback {
        fun onSocketOpened()
        fun onSocketClosed()
        fun onDataRx(data: ByteArray)
    }

    private inner class Session : NsdHelper.DiscoveryCallback {
        val discovery = NsdHelper(context, Consts.SERVICE_TYPE_TCP)
        var searching = true
        var socket: Socket? = null
        val sender: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "ALinkCTxThread") }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) = synchronized(this@ALinkTCPClient) {
            if (current !== this || !searching || serviceInfo.serviceName != serverName) return@synchronized
            val endpoint = InetSocketAddress(serviceInfo.host, serviceInfo.port)
            val connection = Socket()
            socket = connection
            stopSearch()
            Thread({ receive(this, connection, endpoint) }, "ALinkCThread").start()
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

        override fun onDiscoveryStopped() = synchronized(this@ALinkTCPClient) {
            if (current === this && searching) {
                searching = false
                finish(this)
            }
        }

        fun stopSearch() {
            if (!searching) return
            searching = false
            try {
                discovery.stopServiceDiscovery()
            } catch (e: RuntimeException) {
                Log.w(tag, "Could not stop service discovery", e)
            }
        }
    }

    @Synchronized
    fun startDiscovery() {
        if (current != null) return
        val session = Session()
        current = session
        session.discovery.registerDiscoveryCallback(session)
        try {
            session.discovery.discoverServices()
        } catch (e: RuntimeException) {
            Log.w(tag, "Could not start service discovery", e)
            finish(session)
        }
    }

    @Synchronized
    fun stop() {
        current?.let { finish(it) }
    }

    @Synchronized
    fun restart() {
        stop()
        startDiscovery()
    }

    @Synchronized
    private fun finish(session: Session) {
        if (current !== session) return
        current = null
        session.stopSearch()
        session.sender.shutdownNow()
        session.socket?.let {
            it.close()
            callback.onSocketClosed()
        }
    }

    private fun receive(session: Session, socket: Socket, endpoint: InetSocketAddress) {
        try {
            socket.connect(endpoint, 1000)
            socket.soTimeout = 3000
            synchronized(this) {
                if (current !== session) return
                callback.onSocketOpened()
            }
            val frames = TcpFrames(cobsEnable)
            val input = socket.getInputStream()
            val buffer = ByteArray(8192)
            while (!socket.isClosed) {
                val count = input.read(buffer)
                if (count < 0) break
                frames.accept(buffer, count) { raw ->
                    synchronized(this) {
                        if (current === session) {
                            try {
                                val (type, payload) = TCPHeader.fromRaw(raw)
                                when (type) {
                                    USER_DATA -> callback.onDataRx(payload)
                                    PING -> enqueue(session, TCPHeader.toRaw(PING, byteArrayOf()))
                                }
                            } catch (e: Exception) {
                                Log.w(tag, "Invalid ALink message", e)
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            if (!socket.isClosed) Log.w(tag, "TCP connection ended", e)
        } finally {
            finish(session)
        }
    }

    @Synchronized
    fun send(data: ByteArray) {
        current?.let { enqueue(it, TCPHeader.toRaw(USER_DATA, data)) }
    }

    private fun enqueue(session: Session, raw: ByteArray) {
        val socket = session.socket ?: return
        if (!socket.isConnected || socket.isClosed) return
        session.sender.execute {
            try {
                socket.getOutputStream().write(TcpFrames.encode(cobsEnable, raw))
            } catch (e: IOException) {
                socket.close()
            }
        }
    }
}
