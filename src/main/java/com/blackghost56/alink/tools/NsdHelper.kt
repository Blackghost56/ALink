package com.blackghost56.alink.tools

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.Semaphore

class NsdHelper(
    context: Context,
    val serviceType: String
) {
    private val TAG = NsdHelper::class.java.simpleName

    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: DiscoveryListener? = null
    private val serviceList: MutableList<NsdServiceInfo> = ArrayList()
    private val serviceResolveSemaphore = Semaphore(1)
    private var discoveryCallback: DiscoveryCallback? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    var serviceInfo: NsdServiceInfo? = null

    fun getServiceList(): List<NsdServiceInfo> {
        return serviceList
    }

    interface DiscoveryCallback {
        fun onServiceResolved(serviceInfo: NsdServiceInfo)
        fun onServiceLost(serviceInfo: NsdServiceInfo)
    }

    fun registerDiscoveryCallback(discoveryCallback: DiscoveryCallback) {
        this.discoveryCallback = discoveryCallback
    }

    fun registerService(name: String, port: Int = 80, callback: NsdManager.RegistrationListener) {
        registrationListener = callback
        val serviceInfo = NsdServiceInfo()
        serviceInfo.serviceName = name
        serviceInfo.serviceType = serviceType
        serviceInfo.port = port
        this.serviceInfo = serviceInfo

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun unregisterService() {
        if (registrationListener == null) {
            nsdManager.unregisterService(registrationListener)
        } else {
            Log.w(TAG, "Service has already been unregistered")
        }
    }

    fun discoverServices() {
        if (discoveryListener == null) {
            discoveryListener = DiscoveryListener()
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            Log.w(TAG, "Started services discovery")
        } else {
            Log.w(TAG, "Discovery has already been started")
        }
    }

    fun stopServiceDiscovery() {
        if (discoveryListener != null) {
            nsdManager.stopServiceDiscovery(discoveryListener)
            serviceList.clear()
            Log.i(TAG, "Stopped services discovery")
        } else {
            Log.w(TAG, "Discovery has already been stopped")
        }
    }

    private inner class DiscoveryListener : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d(TAG, "Service discovery started")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            //Log.d(TAG, "onServiceFound: " + serviceInfo.getServiceName());
            Thread({
                if (serviceInfo.serviceType != serviceType) {
                    Log.d(TAG, "Unknown Service Type: " + serviceInfo.serviceType)
                } else {
                    Log.i(TAG, "Found service: $serviceInfo")
                    resolveService(serviceInfo)
                }
            }, TAG).start()
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Thread({
                Log.e(TAG, "Service lost: " + service.serviceName)
                val it = serviceList.iterator()
                while (it.hasNext()) {
                    val serviceInfo = it.next()
                    if (serviceInfo.serviceName == service.serviceName) {
                        it.remove()
                        discoveryCallback!!.onServiceLost(serviceInfo)
                        break
                    }
                }
            }, TAG).start()
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(TAG, "Discovery stopped: $serviceType")
            discoveryListener = null
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery starting failed. Error code: $errorCode")
            discoveryListener = null
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery stopping failed. Error code: $errorCode")
            discoveryListener = null
        }
    }


    private fun resolveService(serviceInfo: NsdServiceInfo) {
        try {
            serviceResolveSemaphore.acquire()
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    serviceResolveSemaphore.release()
                    Log.w(TAG, "Resolve failed: $errorCode  $serviceInfo")
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    Thread({
                        Log.i(TAG, "Service resolve: $serviceInfo")
                        serviceResolveSemaphore.release()
                        serviceList.add(serviceInfo)
                        if (discoveryCallback != null) discoveryCallback!!.onServiceResolved(serviceInfo)
                    }, TAG).start()
                }
            })
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }
}