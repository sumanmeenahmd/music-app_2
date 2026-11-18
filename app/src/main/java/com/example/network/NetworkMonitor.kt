package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkMonitor(context: Context) {
  private val connectivityManager =
    context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  private val _isOnline = MutableStateFlow(checkCurrentlyOnline())
  val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

  private val networkCallback =
    object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        _isOnline.value = checkCurrentlyOnline()
      }

      override fun onLost(network: Network) {
        _isOnline.value = checkCurrentlyOnline()
      }

      override fun onCapabilitiesChanged(
        network: Network,
        networkCapabilities: NetworkCapabilities,
      ) {
        _isOnline.value = checkCurrentlyOnline()
      }
    }

  fun start() {
    _isOnline.value = checkCurrentlyOnline()
    runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
  }

  fun stop() {
    runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
  }

  private fun checkCurrentlyOnline(): Boolean {
    val activeNetwork = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
      (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
  }
}
