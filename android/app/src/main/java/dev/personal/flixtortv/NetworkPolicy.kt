package dev.personal.flixtortv

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetAddress

/** Enforced again on every actual DNS resolution, including media redirects. */
internal object NetworkPolicy : Dns {
    fun url(value: String): HttpUrl = value.toHttpUrl().also {
        require(it.scheme == "https" && it.port == 443 && it.username.isEmpty() && it.password.isEmpty())
        require(it.host.contains('.') && !it.host.endsWith(".local") && !it.host.matches(Regex("[0-9.]+")) && !it.host.contains(':'))
    }
    override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
        addresses.forEach { address ->
            require(!address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isSiteLocalAddress && !address.isMulticastAddress)
            val bytes = address.address
            require(bytes.size != 16 || bytes[0].toInt() and 0xfe != 0xfc)
        }
    }
}
