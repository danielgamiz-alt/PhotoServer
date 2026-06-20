package com.photosync.app

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

data class DiscoveredServer(
    val address: String,
    val port: Int,
    val name: String,
    val serverId: String,
    val requiresApiKey: Boolean,
) {
    val baseUrl: String get() = "http://$address:$port"
}

/**
 * Finds PhotoServers on the local network by broadcasting a UDP probe and
 * collecting replies for a short window. Blocking; run on Dispatchers.IO.
 */
object ServerDiscovery {

    private const val DISCOVERY_PORT = 38899
    private const val PROBE = "PHOTOSERVER_DISCOVER_V1"
    private const val LISTEN_WINDOW_MS = 2500

    fun discover(context: Context): List<DiscoveredServer> {
        // Some devices filter broadcast/multicast packets unless a
        // multicast lock is held.
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("photosync-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }

        val found = LinkedHashMap<String, DiscoveredServer>()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 500

                val probe = PROBE.encodeToByteArray()
                socket.send(
                    DatagramPacket(probe, probe.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT)
                )

                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + LISTEN_WINDOW_MS
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    parseReply(packet)?.let { found[it.serverId] = it }
                }
            }
        } finally {
            lock.release()
        }
        return found.values.toList()
    }

    private fun parseReply(packet: DatagramPacket): DiscoveredServer? {
        return try {
            val json = JSONObject(String(packet.data, 0, packet.length))
            if (json.optString("type") != "PHOTOSERVER_HERE_V1") return null
            DiscoveredServer(
                address = packet.address.hostAddress ?: return null,
                port = json.getInt("port"),
                name = json.optString("name"),
                serverId = json.getString("serverId"),
                requiresApiKey = json.optBoolean("requiresApiKey"),
            )
        } catch (e: Exception) {
            null
        }
    }
}
