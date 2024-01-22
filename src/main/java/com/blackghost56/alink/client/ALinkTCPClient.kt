package com.blackghost56.alink.client

import android.content.Context
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.blackghost56.alink.Consts
import com.blackghost56.alink.tools.COBS
import com.blackghost56.alink.tools.NsdHelper
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

    interface Callback {
        fun onSocketOpened()
        fun onSocketClosed()
        fun onDataRx(data: ByteArray)
    }

    fun startDiscovery() {
        if (!isRunning) {
            val txThread = HandlerThread("ALinkClientTxThread")
            txThread.start()
            txHandler = Handler(txThread.looper)

            val rxThread = HandlerThread("ALinkClientRxThread")
            rxThread.start()
            rxHandler = Handler(rxThread.looper)

            nsdHelper.registerDiscoveryCallback(object : NsdHelper.DiscoveryCallback {
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "onServiceResolved: $serviceInfo")
                    if (serviceInfo.serviceName == serverName) {
                        openSocket(serviceInfo.host, serviceInfo.port)
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Log.e(TAG, "onServiceLost: $serviceInfo")
                }

            })
            nsdHelper.discoverServices()
        } else {
            Log.w(TAG, "Discovery has already been started")
        }
    }

    fun stop(){
        rxBuf.clear()

        txHandler?.let {
            it.removeCallbacksAndMessages(null)
            it.looper.quit()
        }

        rxHandler?.let {
            it.removeCallbacksAndMessages(null)
            it.looper.quit()
        }

        isRunning = false
    }

    fun restart(){
        stop()
        startDiscovery()
    }

    private fun openSocket(address: InetAddress, port: Int) {
        if (!isRunning){
            val SOCKET_TIMEOUT_MS = 1000
            Thread{
                try {
                    val socket = Socket()
                    this.socket = socket
                    socket.connect(InetSocketAddress(address, port), SOCKET_TIMEOUT_MS)
                    val inputStream = BufferedInputStream(socket.getInputStream())
                    isRunning = true
                    callback.onSocketOpened()
                    while (isRunning && !socket.isClosed){
                        val inSize = inputStream.available()
                        if (inSize <= 0) {
                            Thread.sleep(1)
                            continue
                        }

                        val rxData = ByteArray(inSize)
                        if (inputStream.read(rxData, 0, inSize) == inSize) {
                            rxHandler?.post { processRxData(rxData) }
                        } else {
                            Log.d(TAG, "The size of the file being read is not equal to the available size")
                        }
                    }
                    callback.onSocketClosed()
                    stop()
                } catch (e: Exception) {
                    e.printStackTrace()
                    stop()
                } finally {
                    socket?.close()
                }
            }.start()
        }
    }

    private fun processRxData(data: ByteArray) {
        if (cobsEnable){
            for (value in data) {
                if (value == COBS.EOP) {
                    callback.onDataRx(COBS.decode(rxBuf).toByteArray())
                    rxBuf.clear()
                } else {
                    rxBuf.add(value)
                }
            }
        } else {
            callback.onDataRx(data)
        }
    }

    fun send(data: ByteArray) {
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
                        stop()
                    }
                } else {
                    Log.d(TAG, "The socket is not connected")
                }
            }
        }
    }

}