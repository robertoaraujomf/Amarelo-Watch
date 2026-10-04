package br.com.amarelowatch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object Net {

    fun localIp(context: Context): String? {
        lanIp(context)?.let { return it }
        return interfaceIp { !it.isLoopbackAddress && it.isSiteLocalAddress && it is Inet4Address }
    }

    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun url(ip: String, port: Int): String = "http://$ip:$port"

    private fun lanIp(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) return null

        val properties = cm.getLinkProperties(network) ?: return null
        return properties.linkAddresses
            .mapNotNull { it.address as? Inet4Address }
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }

    private fun interfaceIp(predicate: (InetAddress) -> Boolean): String? = try {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.asSequence()
            ?.filter { nic -> nic.isUp && !nic.isLoopback() }
            ?.flatMap { nic -> nic.inetAddresses.toList().asSequence() }
            ?.firstOrNull(predicate)
            ?.hostAddress
    } catch (t: Throwable) {
        null
    }
}
