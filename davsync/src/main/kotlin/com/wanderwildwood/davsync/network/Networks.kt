package com.wanderwildwood.davsync.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** What kind of network the phone is on, for the Wi-Fi only setting. */
object Networks {
    /**
     * Whether the phone's network now is Wi-Fi (or a cable). One the phone cannot say anything
     * about counts as not, so a Wi-Fi only sync waits rather than spends mobile data.
     */
    fun onWifi(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(false)
}
