package com.blackghost56.alink.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.blackghost56.alink.Consts
import com.blackghost56.alink.NsdHelper
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket

class ALinkTCPServer(
    private val context: Context,
    val name: String,
    val port: Int,
    private val callback: Callback
) {
    private val TAG = ALinkTCPServer::class.java.simpleName

    private var nsdHelper = NsdHelper(context, Consts.SERVICE_TYPE_TCP)
    private var serverSocket: ServerSocket? = null
    @Volatile private var isRunning = false
    private var internalIncrementAddress = 0;
    private val addressMap = mutableMapOf<Int, Socket>()
    private var txHandler: Handler? = null
    private var rxHandler: Handler? = null

    interface Callback {
        fun onSuccessStart()
        fun onErrorStart(msg: String)
        fun onStop()
        fun onNewConnection(address: Int)
        fun onDataRx(address: Int, data: ByteArray)
    }

    fun start(){
        // Try to register NSD service
        nsdHelper.registerService(name = name, port = port, callback = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(p0: NsdServiceInfo?) {
                Log.i(TAG, "onServiceRegistered: $p0")
                openServerSocket()
            }

            override fun onRegistrationFailed(p0: NsdServiceInfo?, p1: Int) {
                Log.e(TAG, "onRegistrationFailed: $p0")
                callback.onErrorStart("Filed registration NSD service")
            }

            override fun onServiceUnregistered(p0: NsdServiceInfo?) {
                Log.i(TAG, "onServiceUnregistered: $p0")
            }

            override fun onUnregistrationFailed(p0: NsdServiceInfo?, p1: Int) {
                Log.e(TAG, "onUnregistrationFailed: $p0")
            }
        })
    }

    fun stop(){
        isRunning = false
        nsdHelper.unregisterService()

        txHandler?.let {
            it.removeCallbacksAndMessages(null)
            it.looper.quit()
        }

        rxHandler?.let {
            it.removeCallbacksAndMessages(null)
            it.looper.quit()
        }

        addressMap.clear()
        internalIncrementAddress = 0

        callback.onStop()
    }

    private fun openServerSocket(){
        val txThread = HandlerThread("ALinkServerTxThread")
        txThread.start()
        txHandler = Handler(txThread.looper)

        val rxThread = HandlerThread("ALinkServerRxThread")
        rxThread.start()
        rxHandler = Handler(rxThread.looper)

        isRunning = true
        Thread {
            try {
                serverSocket = ServerSocket(port)
                callback.onSuccessStart()
                while (isRunning) {
                    serverSocket?.accept()?.let { socket ->
                        clientHandler(socket)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                stop()
            }
        }.start()
    }

    private fun clientHandler(socket: Socket) {
        val thisAddress = internalIncrementAddress++
        addressMap[thisAddress] = socket
        callback.onNewConnection(thisAddress)
        val ip = socket.inetAddress.hostAddress ?: "Unknown"
        Log.d(TAG, "New connection: $thisAddress, ip: $ip")
        Thread {
            val inputStream = BufferedInputStream(socket.getInputStream())
            while (!socket.isClosed && isRunning) {
                val inSize = inputStream.available()
                if (inSize <= 0) {
                    Thread.sleep(1)
                    continue
                }

                val rxData = ByteArray(inSize)
                if (inputStream.read(rxData, 0, inSize) == inSize) {
                    rxHandler?.post { callback.onDataRx(thisAddress, rxData) }
                } else {
                    Log.d(TAG, "The size of the file being read is not equal to the available size")
                }
            }
            addressMap.remove(thisAddress)
            Log.d(TAG, "Disconnect: $thisAddress, ip: $ip")
        }.start()
    }


    fun send(address: Int, data: ByteArray) {
        addressMap[address]?.let {
            txHandler?.post {
                if (isRunning && !it.isClosed) {
                    try {
                        it.getOutputStream().write(data)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        addressMap.remove(address)
                    }
                } else {
                    Log.d(TAG, "The socket is not connected")
                }
            }
        }
    }


    fun getClientList(): List<Int> {
        return addressMap.keys.toList()
    }

}