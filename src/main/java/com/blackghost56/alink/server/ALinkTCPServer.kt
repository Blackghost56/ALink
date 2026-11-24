package com.blackghost56.alink.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.blackghost56.alink.Consts
import com.blackghost56.alink.tools.TcpFrames
import android.os.SystemClock
import com.blackghost56.alink.tools.NsdHelper
import com.blackghost56.alink.tools.TCPHeader
import com.blackghost56.alink.tools.TCPHeader.TCPMessageType.PING
import com.blackghost56.alink.tools.TCPHeader.TCPMessageType.USER_DATA
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.ServerSocket
import java.net.Socket


open class ALinkTCPServer @JvmOverloads constructor(
    context: Context,
    var name: String,
    private val port: Int,
    private val callback: Callback,
    private val cobsEnable: Boolean = true,
    private val localOnly: Boolean = false
) {
    private val TAG = ALinkTCPServer::class.java.simpleName

    private var nsdHelper = NsdHelper(context, Consts.SERVICE_TYPE_TCP)
    private var registration: Registration? = null
    val listeningPort: Int?
        @Synchronized get() = registration?.takeIf { isRunning }?.socket?.localPort
    private var startRequested = false
    @Volatile private var isRunning = false
    private var internalIncrementAddress = 0
    private val clientMap = mutableMapOf<Int, Client>()
    private var txHandler: Handler? = null
    private var rxHandler: Handler? = null


    private class Client(val socket: Socket)

    interface Callback {
        fun onSuccessStart()
        fun onError(msg: String)
        //        fun onStop()
        fun onNewConnection(address: Int)
        fun onDisconnect(address: Int)
        fun onDataRx(address: Int, data: ByteArray)
    }


    @Synchronized
    fun start() {
        startRequested = true
        val active = registration
        if (active == null) {
            val next = Registration(name)
            registration = next
            Thread({ bindAndRegister(next) }, "ALinkSBind").start()
        } else if (active.closing) {
            unregisterIfReady(active)
        }
    }

    @Synchronized
    fun stop() {
        startRequested = false
        val active = registration ?: return
        active.closing = true
        isRunning = false
        active.socket?.close()
        txHandler?.looper?.quit()
        rxHandler?.looper?.quit()
        for ((address, client) in clientMap.toMap()) disconnect(address, client)
        if (localOnly) {
            finishRegistration()
        } else if (active.socket == null) {
            // The bind worker has not started yet.
            registration = null
        } else {
            unregisterIfReady(active)
        }
    }

    @Synchronized
    fun restart() {
        stop()
        start()
    }

    @Synchronized
    private fun bindAndRegister(active: Registration) {
        if (registration !== active) return
        try {
            val socket = if (localOnly) ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1"))
                         else ServerSocket(port)
            active.socket = socket
            if (localOnly) startListening(active, socket)
            else nsdHelper.registerService(active.serviceName, socket.localPort, active)
        } catch (e: Exception) {
            registrationFailed(active, e.toString())
        }
    }

    private inner class Registration(val serviceName: String) : NsdManager.RegistrationListener {
        var socket: ServerSocket? = null
        var registered = false
        var closing = false
        var unregistering = false

        override fun onServiceRegistered(info: NsdServiceInfo?) = synchronized(this@ALinkTCPServer) {
            if (registration !== this) return@synchronized
            registered = true
            if (closing) {
                unregisterIfReady(this)
            } else {
                startListening(this, requireNotNull(socket))
            }
        }

        override fun onRegistrationFailed(info: NsdServiceInfo?, error: Int) =
            registrationFailed(this, "NSD registration failed ($error)")

        override fun onServiceUnregistered(info: NsdServiceInfo?) = synchronized(this@ALinkTCPServer) {
            if (registration === this) finishRegistration()
        }

        override fun onUnregistrationFailed(info: NsdServiceInfo?, error: Int) = synchronized(this@ALinkTCPServer) {
            if (registration !== this) return@synchronized
            unregistering = false
            callback.onError("NSD unregistration failed ($error)")
        }
    }

    private fun startListening(active: Registration, listener: ServerSocket) {
        txHandler = newHandler("ALinkSTxHandler")
        rxHandler = newHandler("ALinkSRxHandler")
        isRunning = true
        Thread({ acceptClients(active, listener) }, "ALinkSThread").start()
        callback.onSuccessStart()
    }

    private fun newHandler(name: String): Handler {
        val thread = HandlerThread(name)
        thread.start()
        return Handler(thread.looper)
    }

    // Called under the server lock. A stop during registration waits for its callback.
    private fun unregisterIfReady(active: Registration) {
        if (!active.registered || active.unregistering) return
        active.unregistering = true
        try {
            nsdHelper.unregisterService()
        } catch (e: RuntimeException) {
            active.unregistering = false
            callback.onError(e.toString())
        }
    }

    @Synchronized
    private fun registrationFailed(active: Registration, error: String) {
        if (registration !== active) return
        active.socket?.close()
        if (!active.closing) {
            startRequested = false
            callback.onError(error)
        }
        finishRegistration()
    }

    private fun finishRegistration() {
        nsdHelper.registrationListener = null
        registration = null
        if (startRequested) start()
    }

    private fun acceptClients(active: Registration, listener: ServerSocket) {
        try {
            while (!listener.isClosed) {
                val socket = listener.accept()
                synchronized(this) {
                    if (registration === active && !active.closing) clientHandler(socket)
                    else socket.close()
                }
            }
        } catch (e: Exception) {
            synchronized(this) {
                if (registration === active && !active.closing) {
                    stop()
                    callback.onError(e.toString())
                }
            }
        }
    }
    private fun clientHandler(socket: Socket) {
        val thisAddress = internalIncrementAddress++
        val client = Client(socket)
        clientMap[thisAddress] = client
        callback.onNewConnection(thisAddress)
        val ip = socket.inetAddress.hostAddress ?: "Unknown"
        Log.d(TAG, "New connection: $thisAddress, ip: $ip")
        val handler = requireNotNull(rxHandler)
        Thread({
            val frames = TcpFrames(cobsEnable)
            try {
                socket.soTimeout = 1000
                val input = socket.getInputStream()
                val buffer = ByteArray(8192)
                var lastReceived = SystemClock.elapsedRealtime()
                while (!socket.isClosed) {
                    val count = try {
                        input.read(buffer)
                    } catch (_: SocketTimeoutException) {
                        if (SystemClock.elapsedRealtime() - lastReceived >= 5000) break
                        sendLL(thisAddress, PING, byteArrayOf())
                        continue
                    }
                    if (count < 0) break
                    lastReceived = SystemClock.elapsedRealtime()
                    val bytes = buffer.copyOf(count)
                    handler.post {
                        if (!socket.isClosed) {
                            try {
                                frames.accept(bytes, bytes.size) { onDataRx(thisAddress, it) }
                            } catch (e: IOException) {
                                disconnect(thisAddress, client)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                if (!socket.isClosed) Log.w(TAG, "Client read failed", e)
            } finally {
                // Deliver queued data before reporting EOF. stop() handles a terminated looper.
                if (!handler.post { disconnect(thisAddress, client) }) disconnect(thisAddress, client)
            }
        }, "ALinkSThread_$thisAddress").start()
    }

    @Synchronized
    private fun disconnect(address: Int, client: Client) {
        if (clientMap[address] !== client) return
        clientMap.remove(address)
        client.socket.close()
        callback.onDisconnect(address)
    }
    private fun onDataRx(address: Int, data: ByteArray) {
        try {
            val msg = TCPHeader.fromRaw(data)
            when (msg.first){
                USER_DATA -> callback.onDataRx(address, msg.second)
                PING -> Unit
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun send(address: Int, data: ByteArray) {
        sendLL(address, TCPHeader.toRaw(USER_DATA, data))
    }

    private fun sendLL(address: Int, msgType: TCPHeader.TCPMessageType, data: ByteArray){
        sendLL(address, TCPHeader.toRaw(msgType, data))
    }

    @Synchronized
    private fun sendLL(address: Int, data: ByteArray) {
        clientMap[address]?.let { client ->
            txHandler?.post {
                if (isRunning && !client.socket.isClosed) {
                    try {
                        client.socket.getOutputStream().write(TcpFrames.encode(cobsEnable, data))
                    } catch (e: Exception) {
                        e.printStackTrace()
                        disconnect(address, client)
                    }
                } else {
                    Log.d(TAG, "The socket is not connected")
                }
            }
        }
    }

    @Synchronized
    fun getClientList(): List<Int> {
        return clientMap.keys.toList()
    }

    @Synchronized
    fun getClientAddressList(): List<String> {
        return clientMap.values.map { it.socket.inetAddress.hostAddress ?: "Unknown" }
    }
}
