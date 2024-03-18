package com.blackghost56.alink.client

import android.content.Context
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket


class ALinkTCPClient(
    private val context: Context,
    var serverName: String,
    private val callback: Callback,
    val cobsEnable: Boolean = true
) {
    private val TAG = ALinkTCPClient::class.java.simpleName

    private var nsdHelper = NsdHelper(context, Consts.SERVICE_TYPE_TCP)
    private var socket: Socket? = null
    @Volatile private var isRunning = false
    private var txHandler: Handler? = null
    private var rxHandler: Handler? = null
    private var rxBuf = mutableListOf<Byte>()
    private var stopDiscoveryResult = false

    interface Callback {
        fun onSocketOpened()
        fun onSocketClosed()
        fun onDataRx(data: ByteArray)
    }

    fun startDiscovery() {
        if (!isRunning) {
            val txThread = HandlerThread("ALinkCTxHandler")
            txThread.start()
            txHandler = Handler(txThread.looper)

            val rxThread = HandlerThread("ALinkCRxHandler")
            rxThread.start()
            rxHandler = Handler(rxThread.looper)

            nsdHelper.registerDiscoveryCallback(object : NsdHelper.DiscoveryCallback {
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "onServiceResolved: $serviceInfo")
                    if (serviceInfo.serviceName == serverName && !isRunning) {
                        openSocket(serviceInfo.host, serviceInfo.port)
                        nsdHelper.stopServiceDiscovery()
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Log.e(TAG, "onServiceLost: $serviceInfo")
                }

                override fun onDiscoveryStopped() {
                    stopDiscoveryResult = true
                }

            })
            nsdHelper.discoverServices()
        } else {
            Log.w(TAG, "Discovery has already been started")
        }
    }

     fun stop(){
        isRunning = false

        txHandler?.let {
            it.removeCallbacksAndMessages(null)
            it.looper.quit()
        }

        rxHandler?.let {
            it.removeCallbacksAndMessages(null)
            it.looper.quit()
        }

        stopDiscoveryResult = false
        nsdHelper.stopServiceDiscovery()
        while (nsdHelper.discoveryListener != null && !stopDiscoveryResult) {
            Thread.sleep(1)
        }
    }

    fun restart(){
        stop()
        startDiscovery()
    }

    private fun openSocket(address: InetAddress, port: Int) {
        if (!isRunning){
            Thread({
                try {
                    val socket = Socket()
                    Log.d(TAG, "New socket")
                    this.socket = socket
                    socket.connect(InetSocketAddress(address, port), 1000)
                    val inputStream = BufferedInputStream(socket.getInputStream())
                    isRunning = true
                    callback.onSocketOpened()
                    val rxTimeout = 3000
                    var lastRxTime: Long = System.currentTimeMillis()
                    while (isRunning && !socket.isClosed){
                        if (System.currentTimeMillis() - lastRxTime > rxTimeout) {
                            Log.e(TAG, "The connection has timed out")
                            break
                        }

                        val inSize = inputStream.available()
                        if (inSize <= 0) {
                            Thread.sleep(1)
                            continue
                        }

                        lastRxTime = System.currentTimeMillis()
                        val rxData = ByteArray(inSize)
                        if (inputStream.read(rxData, 0, inSize) == inSize) {
                            rxHandler?.post { processRxData(rxData) }
                        } else {
                            Log.d(TAG, "The size of the file being read is not equal to the available size")
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    socket?.close()
                    rxBuf.clear()
                    callback.onSocketClosed()
                    Log.d(TAG, "Socket closed")
                }
            }, "ALinkCThread").start()
        }
    }

    private fun processRxData(data: ByteArray) {
        if (cobsEnable){
            for (value in data) {
                if (value == COBS.EOP) {
                    onDataRx(COBS.decode(rxBuf).toByteArray())
                    rxBuf.clear()
                } else {
                    rxBuf.add(value)
                }
            }
        } else {
            onDataRx(data)
        }
    }

    private fun onDataRx(data: ByteArray) {
        try {
            val msg = TCPHeader.fromRaw(data)
            when (msg.first){
                USER_DATA -> callback.onDataRx(msg.second)
                PING -> {
                    sendLL(TCPHeader.toRaw(PING, ByteArray(0)))
//                    Log.d(TAG, "Rx Ping")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun send(data: ByteArray) {
        sendLL(TCPHeader.toRaw(USER_DATA, data))
    }

    private fun sendLL(type: TCPHeader.TCPMessageType, data: ByteArray){
        sendLL(TCPHeader.toRaw(type, data))
        Log.d(TAG, "Tx: $type")
    }

    private fun sendLL(data: ByteArray) {
        socket?.let {
            txHandler?.post {
                if (isRunning && !it.isClosed) {
                    try {
                        if (cobsEnable){
                            it.getOutputStream().write(COBS.encode(data.toList()).toByteArray())
                        } else {
                            it.getOutputStream().write(data)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    Log.d(TAG, "The socket is not connected")
                }
            }
        }
    }

}