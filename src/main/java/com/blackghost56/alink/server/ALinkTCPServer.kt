package com.blackghost56.alink.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.blackghost56.alink.Consts
import com.blackghost56.alink.tools.CobsFrameReader
import com.blackghost56.alink.tools.COBS
import com.blackghost56.alink.tools.NsdHelper
import com.blackghost56.alink.tools.TCPHeader
import com.blackghost56.alink.tools.TCPHeader.TCPMessageType.PING
import com.blackghost56.alink.tools.TCPHeader.TCPMessageType.USER_DATA
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

open class ALinkTCPServer(
    context: Context,
    var name: String,
    private val port: Int,
    private val callback: Callback,
    private val cobsEnable: Boolean = true
) {
    private val TAG = ALinkTCPServer::class.java.simpleName

    private var nsdHelper = NsdHelper(context, Consts.SERVICE_TYPE_TCP)
    private var registration: Registration? = null
    private var startRequested = false
    @Volatile private var isRunning = false
    private var internalIncrementAddress = 0
    private val clientMap = ConcurrentHashMap<Int, Client>()
    private var txHandler: Handler? = null
    private var rxHandler: Handler? = null


    private data class Client(
        val socket: Socket,
        val frames: CobsFrameReader = CobsFrameReader(),
        var lastTxPingTime: Long = 0,
        var lastRxTime: Long = 0
    )

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
        for (client in clientMap.values) client.socket.close()
        if (active.socket == null) {
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
            val socket = ServerSocket(port)
            active.socket = socket
            nsdHelper.registerService(active.serviceName, socket.localPort, active)
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
                val listener = requireNotNull(socket)
                txHandler = newHandler("ALinkSTxHandler")
                rxHandler = newHandler("ALinkSRxHandler")
                isRunning = true
                Thread({ acceptClients(this, listener) }, "ALinkSThread").start()
                callback.onSuccessStart()
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
        Thread ({
            try {
                val rxTimeout = 5000
                val txPingTimeout = 1000
                client.lastRxTime = System.currentTimeMillis()
                val inputStream = BufferedInputStream(socket.getInputStream())
                while (!socket.isClosed && isRunning) {
                    // Updating the channel status
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - client.lastTxPingTime > txPingTimeout &&
                        currentTime - client.lastRxTime > txPingTimeout
                    ) {
                        sendLL(thisAddress, PING, byteArrayOf())
                        client.lastTxPingTime = currentTime
//                        Log.d(TAG, "Tx ping: $thisAddress, ip: $ip")
                    }
                    if (currentTime - client.lastRxTime > rxTimeout) {
                        Log.d(TAG, "Rx timeout: $thisAddress, ip: $ip     ${currentTime - client.lastRxTime}")
                        break
                    }

                    val inSize = inputStream.available()
                    if (inSize <= 0) {
                        Thread.sleep(1)
                        continue
                    }

                    client.lastRxTime = System.currentTimeMillis()
                    val rxData = ByteArray(inSize)
                    if (inputStream.read(rxData, 0, inSize) == inSize) {
                        rxHandler?.post { processRxData(thisAddress, rxData) }
                    } else {
                        Log.d(TAG, "The size of the file being read is not equal to the available size")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                socket.close()
                clientMap.remove(thisAddress)
                callback.onDisconnect(thisAddress)
                Log.d(TAG, "Disconnect: $thisAddress, ip: $ip")
            }
        }, "ALinkSThread_$thisAddress").start()
    }

    private fun processRxData(address: Int, data: ByteArray) {
        if (cobsEnable) {
            clientMap[address]?.frames?.accept(data) { onDataRx(address, it) }
        } else {
            onDataRx(address, data)
        }
    }

    private fun onDataRx(address: Int, data: ByteArray) {
        try {
            val msg = TCPHeader.fromRaw(data)
            when (msg.first){
                USER_DATA -> callback.onDataRx(address, msg.second)
                PING -> {
                    clientMap[address]?.lastRxTime = System.currentTimeMillis()
//                    Log.d(TAG, "Rx Ping $address")
                }
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

    private fun sendLL(address: Int, data: ByteArray) {
        clientMap[address]?.let { client ->
            txHandler?.post {
                if (isRunning && !client.socket.isClosed) {
                    try {
                        if (cobsEnable){
                            client.socket.getOutputStream().write(COBS.encode(data.toList()).toByteArray())
                        } else {
                            client.socket.getOutputStream().write(data)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        clientMap.remove(address)
                        client.socket.close()
                    }
                } else {
                    Log.d(TAG, "The socket is not connected")
                }
            }
        }
    }

    fun getClientList(): List<Int> {
        return clientMap.keys.toList()
    }

    fun getClientAddressList(): List<String> {
        return mutableListOf<Client>().apply {
            addAll(clientMap.values)
        }.map { it.socket.inetAddress.hostAddress ?: "Unknown" }.toList()
    }

}
