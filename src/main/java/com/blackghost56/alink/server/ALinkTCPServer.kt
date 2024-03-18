package com.blackghost56.alink.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.blackghost56.alink.Consts
import com.blackghost56.alink.tools.COBS
import com.blackghost56.alink.tools.NsdHelper
import com.blackghost56.alink.tools.TCPHeader
import com.blackghost56.alink.tools.TCPHeader.TCPMessageType.PING
import com.blackghost56.alink.tools.TCPHeader.TCPMessageType.USER_DATA
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

open class ALinkTCPServer(
    context: Context,
    var name: String,
    private val port: Int,
    private val callback: Callback,
    private val cobsEnable: Boolean = true
) {
    private val TAG = ALinkTCPServer::class.java.simpleName

    private var nsdHelper = NsdHelper(context, Consts.SERVICE_TYPE_TCP)
    private var serverSocket: ServerSocket? = null
    @Volatile private var isRunning = false
    private var internalIncrementAddress = 0
//    private val socketMap = mutableMapOf<Int, Socket>()
//    private val rxBufMap = mutableMapOf<Int, MutableList<Byte>>()
    private val clientMap = mutableMapOf<Int, Client>()
    private var txHandler: Handler? = null
    private var rxHandler: Handler? = null


    private data class Client(
        val socket: Socket,
        var rxBuffer: MutableList<Byte> = mutableListOf(),
        var lastTxTime: Long = 0,
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


    fun start(){
        if (isRunning || nsdHelper.registrationListener != null)
            return

        // Try to register NSD service
        nsdHelper.registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(p0: NsdServiceInfo?) {
                Log.i(TAG, "onServiceRegistered: $p0")
                openServerSocket()
            }

            override fun onRegistrationFailed(p0: NsdServiceInfo?, p1: Int) {
                Log.e(TAG, "onRegistrationFailed: $p0")
                callback.onError("Filed registration NSD service")
                isRunning = false
                nsdHelper.registrationListener = null
            }

            override fun onServiceUnregistered(p0: NsdServiceInfo?) {
                Log.i(TAG, "onServiceUnregistered: $p0")
                isRunning = false
                nsdHelper.registrationListener = null
            }

            override fun onUnregistrationFailed(p0: NsdServiceInfo?, p1: Int) {
                Log.e(TAG, "onUnregistrationFailed: $p0")
            }
        }
        nsdHelper.registrationListener?.let {
            nsdHelper.registerService(name = name, port = port, callback = it)
        }
    }

    fun stop(){
        isRunning = false
        serverSocket?.close()
        nsdHelper.unregisterService()

        txHandler?.let {
            it.removeCallbacksAndMessages(null)
            it.looper.quit()
        }

        rxHandler?.let {
            it.removeCallbacksAndMessages(null)
            it.looper.quit()
        }

        clientMap.clear()
        internalIncrementAddress = 0

//        callback.onStop()
    }

    fun restart(){
        stop()
        start()
    }

    private fun openServerSocket(){
        val txThread = HandlerThread("ALinkSTxHandler")
        txThread.start()
        txHandler = Handler(txThread.looper)

        val rxThread = HandlerThread("ALinkSRxHandler")
        rxThread.start()
        rxHandler = Handler(rxThread.looper)

        Thread ({
            try {
                serverSocket = ServerSocket(port)
                callback.onSuccessStart()
                isRunning = true
                while (isRunning) {
                    serverSocket?.accept()?.let { socket ->
                        clientHandler(socket)
                    }
                }
            } catch (_: SocketException){

            } catch (e: Exception) {
                e.printStackTrace()
                callback.onError(e.message.toString())
            } finally {
                //serverSocket?.close()
                isRunning = false
            }
        }, "ALinkSThread").start()
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
                val rxTimeout = 3000
                val txTimeout = 1000
                val inputStream = BufferedInputStream(socket.getInputStream())
                while (!socket.isClosed && isRunning) {
                    // Updating the channel status
                    val currentTime = System.currentTimeMillis()
                    if (currentTime > client.lastTxTime + txTimeout) {
                        sendLL(thisAddress, PING, byteArrayOf())
                        Log.d(TAG, "Tx ping: $thisAddress, ip: $ip")
                    }
                    if (currentTime > client.lastRxTime + rxTimeout) {
                        Log.d(TAG, "Rx timeout: $thisAddress, ip: $ip")
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
        if (cobsEnable){
            clientMap[address]?.let { client ->
                for (value in data) {
                    if (value == COBS.EOP) {
                        onDataRx(address, COBS.decode(client.rxBuffer).toByteArray())
                        client.rxBuffer.clear()
                    } else {
                        client.rxBuffer.add(value)
                    }
                }
            }
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
                    clientMap[address]?.lastTxTime = System.currentTimeMillis()
                    Log.d(TAG, "Rx Ping $address")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun send(address: Int, data: ByteArray) {
        sendLL(address, TCPHeader.toRaw(USER_DATA, data))
    }

    private fun sendLL(address: Int, type: TCPHeader.TCPMessageType, data: ByteArray){
        sendLL(address, TCPHeader.toRaw(type, data))
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
                        client.lastTxTime = System.currentTimeMillis()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        clientMap.remove(address)
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