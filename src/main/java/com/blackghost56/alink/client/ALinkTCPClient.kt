package com.blackghost56.alink.client

import android.content.Context
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.blackghost56.alink.Consts
import com.blackghost56.alink.NsdHelper
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket


class ALinkTCPClient(
    private val context: Context,
    val serverName: String,
    private val callback: Callback
) {
    private val TAG = ALinkTCPClient::class.java.simpleName

    private var nsdHelper = NsdHelper(context, Consts.SERVICE_TYPE_TCP)
    private var socket: Socket? = null
    @Volatile private var isRunning = false
    private var txHandler: Handler? = null
    private var rxHandler: Handler? = null

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
        socket?.close()

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
                        val rxData = ByteArray(inSize)
                        if (inputStream.read(rxData, 0, inSize) == inSize) {
                            rxHandler?.post { callback.onDataRx(rxData) }
                        } else {
                            Log.d(TAG, "The size of the file being read is not equal to the available size")
                        }
                        // Todo
                        Thread.sleep(1)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    isRunning = false
                }
            }.start()
        }
    }


    fun send(data: ByteArray) {
        socket?.let {
            txHandler?.post {
                if (isRunning && it.isConnected) {
                    try {
                        it.getOutputStream().write(data)
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