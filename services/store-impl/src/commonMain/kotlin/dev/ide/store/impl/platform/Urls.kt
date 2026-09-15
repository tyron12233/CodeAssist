package dev.ide.store.impl.platform

/**
 * Percent-encoding and base64url, written out rather than taken from a platform.
 *
 * `java.net.URLEncoder` and `java.util.Base64` are JVM-only, and the two of them are the whole of what
 * the sign-in flow needs beyond HTTP: an authorize URL to build, a redirect to pick apart, and a JWT
 * payload to read an expiry out of. Both are small and exactly specified, so a shared implementation is
 * also the one that cannot disagree with itself across hosts.
 */

private const val UNRESERVED =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.*"

/**
 * `application/x-www-form-urlencoded`, matching `URLEncoder.encode(s, "UTF-8")` — including its one
 * deviation from RFC 3986, which is that a space becomes `+` rather than `%20`. Kept deliberately: this
 * builds the same authorize URLs the JVM hosts have always sent.
 */
internal fun urlEncode(s: String): String {
    val out = StringBuilder(s.length)
    for (byte in s.encodeToByteArray()) {
        val c = byte.toInt().toChar()
        when {
            c in UNRESERVED -> out.append(c)
            c == ' ' -> out.append('+')
            else -> {
                val v = byte.toInt() and 0xFF
                out.append('%')
                out.append("0123456789ABCDEF"[v ushr 4])
                out.append("0123456789ABCDEF"[v and 0x0F])
            }
        }
    }
    return out.toString()
}

/** The inverse of [urlEncode]. Undecodable input is returned as it came, never thrown over. */
internal fun urlDecode(s: String): String {
    if ('%' !in s && '+' !in s) return s
    val bytes = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '+' -> { bytes.add(' '.code.toByte()); i++ }
            c == '%' && i + 2 < s.length -> {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return s
                bytes.add(hex.toByte())
                i += 3
            }
            else -> { bytes.addAll(c.toString().encodeToByteArray().toList()); i++ }
        }
    }
    return bytes.toByteArray().decodeToString()
}

/**
 * Decode base64url (`-`/`_`, padding optional), as a JWT payload is encoded.
 *
 * Returns null for anything that is not base64 rather than throwing: this reads a token a server issued,
 * and a token that cannot be read means "no expiry to be had", which the caller already handles.
 */
internal fun base64UrlDecode(text: String): ByteArray? {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val out = ArrayList<Byte>(text.length * 3 / 4 + 3)
    var buffer = 0
    var bits = 0
    for (c in text) {
        if (c == '=') break
        val v = alphabet.indexOf(c)
        if (v < 0) return null
        buffer = (buffer shl 6) or v
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.add(((buffer shr bits) and 0xFF).toByte())
        }
    }
    return out.toByteArray()
}
