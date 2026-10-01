package com.ultrabytecoder.coinsafebox.data.walletconnect

/**
 * Parsed WalletConnect pairing URI: `wc:<topic>@<version>?relay-protocol=irn&symKey=<hex>&...`.
 *
 * Note: like the reference SDK, the `wc:` scheme is stripped before parsing, so the
 * leading `<protocol>` segment is typically empty; the meaningful fields are
 * [topic], [version], [symKey], [relayProtocol], [methods], [expiryTimestamp].
 */
data class WcPairingUri(
    val protocol: String,
    val topic: String,
    val version: Int,
    val symKey: String,
    val relayProtocol: String,
    val methods: List<String>?,
    val expiryTimestamp: Long?,
)

object WcUri {

    /**
     * Parses a pairing URI. Accepts a plain `wc:` URI or a base64-encoded `wc:` URI.
     * Tolerates an optional `wc://` scheme prefix.
     */
    fun parseUri(str: String): WcPairingUri {
        var s = str.trim()
        if (!s.contains("wc:")) {
            val decoded = runCatching { WcEncoding.base64Decode(s).decodeToString() }.getOrNull()
            if (decoded != null && decoded.contains("wc:")) s = decoded
        }
        if (s.startsWith("wc://")) s = s.removePrefix("wc://")
        else s = s.removePrefix("wc:")

        val qIdx = s.indexOf('?')
        val pathPart = if (qIdx != -1) s.substring(0, qIdx) else s
        val query = if (qIdx != -1) s.substring(qIdx + 1) else ""

        // pathPart is "<topic>@<version>" (optionally prefixed "<protocol>:" — usually empty).
        val colonIdx = pathPart.indexOf(':')
        val protocol = if (colonIdx >= 0) pathPart.substring(0, colonIdx) else ""
        val path = if (colonIdx >= 0) pathPart.substring(colonIdx + 1) else pathPart

        val required = path.split('@')
        if (required.size < 2) throw WcProtocolException("Invalid pairing URI: missing version")
        val topic = parseTopic(required[0])
        if (topic.isEmpty()) throw WcProtocolException("Invalid pairing URI: empty topic")
        val version = required[1].toIntOrNull()
            ?: throw WcProtocolException("Invalid pairing URI version")

        val params = parseQuery(query)
        val symKey = params["symKey"]
            ?: throw WcProtocolException("Pairing URI missing symKey")
        val relayProtocol = params["relay-protocol"]
            ?: throw WcProtocolException("Pairing URI missing relay-protocol")
        val methods = params["methods"]?.split(',')?.filter { it.isNotEmpty() }
        val expiryTimestamp = params["expiryTimestamp"]?.toLongOrNull()

        return WcPairingUri(
            protocol = protocol,
            topic = topic,
            version = version,
            symKey = symKey,
            relayProtocol = relayProtocol,
            methods = methods,
            expiryTimestamp = expiryTimestamp,
        )
    }

    fun parseTopic(topic: String): String = if (topic.startsWith("//")) topic.substring(2) else topic

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val k = WcEncoding.urlDecode(pair.substring(0, idx))
            val v = WcEncoding.urlDecode(pair.substring(idx + 1))
            k to v
        }.toMap()
    }
}
