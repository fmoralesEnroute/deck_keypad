package com.example.macdeck

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

fun interface NetworkMonitor {
    /** Emits whenever the phone switches to a different default network (e.g. Wi-Fi comes back). */
    fun networkChanges(): Flow<Unit>

    companion object {
        val None = NetworkMonitor { emptyFlow() }
    }
}

class AndroidNetworkMonitor(context: Context) : NetworkMonitor {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    override fun networkChanges(): Flow<Unit> = callbackFlow {
        // Registering immediately reports the current network; that isn't a change, so skip it.
        val current = cm.activeNetwork
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network != current) trySend(Unit)
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }
}
