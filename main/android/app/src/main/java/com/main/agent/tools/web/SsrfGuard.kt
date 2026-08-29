package com.main.agent.tools.web

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

class SsrfBlockedException(message: String) : Exception(message)

/**
 * Shared SSRF defense for BrowserTool/DownloadTool.
 *
 * A hostname string-prefix denylist (the original approach) is bypassable via DNS rebinding
 * (a public name resolving to a private IP), alternate IPv4 encodings (hex/octal/integer —
 * which [InetAddress] itself normalizes), and IPv6 private/ULA/link-local ranges. So instead
 * this resolves the host and inspects the actual address(es), and — since a 30x redirect can
 * point anywhere — every hop must be re-validated too; trusting OkHttp's automatic follower to
 * only redirect somewhere "safe" is exactly the bypass this exists to close.
 */
internal object SsrfGuard {

    private val DENY_HOSTNAMES = setOf("metadata.google.internal")

    /** Null if [rawUrl]'s scheme+host are acceptable; otherwise a user-facing reason. */
    fun validate(rawUrl: String): String? {
        if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
            return "URL must start with http:// or https://"
        }
        val uri = try { URI(rawUrl) } catch (e: Exception) {
            return "Invalid URL format"
        }
        val host = uri.host?.lowercase() ?: return "URL has no host"

        if (host in DENY_HOSTNAMES || host.endsWith(".local") || host.endsWith(".internal")) {
            return "Cannot reach private/internal hosts"
        }

        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: Exception) {
            return "Cannot resolve host: $host"
        }
        if (addresses.isEmpty()) return "Cannot resolve host: $host"

        addresses.firstOrNull { isDisallowed(it) }?.let { addr ->
            return "Host '$host' resolves to a private/internal address (${addr.hostAddress})"
        }
        return null
    }

    private fun isDisallowed(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
            addr.isAnyLocalAddress || addr.isMulticastAddress) return true
        // IPv6 Unique Local Address fc00::/7 — top 7 bits are 1111_110x — not covered by
        // isSiteLocalAddress (that only recognizes the deprecated fec0::/10 range).
        if (addr is Inet6Address && (addr.address[0].toInt() and 0xFE) == 0xFC) return true
        return false
    }

    /**
     * Executes a GET against [startUrl], manually following up to [maxRedirects] redirects and
     * re-validating each hop. [client] must be built with `followRedirects(false)`.
     */
    fun executeSafely(client: OkHttpClient, startUrl: String, maxRedirects: Int = 5): Response {
        var currentUrl = startUrl
        repeat(maxRedirects + 1) {
            validate(currentUrl)?.let { throw SsrfBlockedException(it) }
            val resp = client.newCall(Request.Builder().url(currentUrl).build()).execute()
            if (resp.code !in 300..399) return resp
            val location = resp.header("Location")
            resp.close()
            if (location == null) throw SsrfBlockedException("Redirect with no Location header")
            currentUrl = URI(currentUrl).resolve(location).toString()
        }
        throw SsrfBlockedException("Too many redirects")
    }
}
