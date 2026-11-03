package dev.ide.ui.backend

/** Why an SDK Manager download or the package list failed, in the terms the user can act on. */
enum class SdkFailureKind {
    /** No connection to the download server (DNS, refused, timed out, unreachable network, TLS handshake). */
    OFFLINE,

    /** The device or disk ran out of space while writing the download. */
    STORAGE_FULL,

    /** The server no longer has the package (HTTP 404/410, or it left the repository manifest). */
    NOT_FOUND,

    /** The server answered with an error status other than not-found ([UiSdkFailure.httpCode]). */
    SERVER,

    /** The package's license has not been accepted. */
    LICENSE,

    /** The downloaded archive was truncated or damaged. */
    CORRUPT,

    /** The user cancelled it; a partial download is kept so it can resume. */
    CANCELLED,

    /** None of the above; the raw message is all there is. */
    UNKNOWN,
}

/** A classified failure: its [kind], and the HTTP status when the server reported one. */
data class UiSdkFailure(val kind: SdkFailureKind, val httpCode: Int? = null)

/**
 * Classifies the failure text the SDK download path produces (an exception's type and message, or the
 * installer's own wording) into a [UiSdkFailure]. The text is matched rather than the exception, because the
 * installer reports failures as strings and an exception type does not cross the common UI boundary.
 */
object SdkFailures {
    private val HTTP_CODE = Regex("""(?:HTTP|response code:?)\s*(\d{3})""", RegexOption.IGNORE_CASE)

    private val OFFLINE_MARKERS = listOf(
        "unknownhostexception", "unable to resolve host", "connectexception", "noroutetohostexception",
        "network is unreachable", "sockettimeoutexception", "timed out", "could not reach", "connection reset",
        "connection refused", "sslhandshakeexception", "sslexception", "no address associated",
    )
    private val STORAGE_MARKERS = listOf(
        "no space left", "enospc", "disk full", "not enough space", "insufficient storage", "edquot",
    )
    private val CORRUPT_MARKERS = listOf(
        "incomplete", "zipexception", "crc", "eofexception", "unexpected end", "has no android.jar",
        "invalid entry", "not in gzip format",
    )

    fun classify(message: String?): UiSdkFailure {
        val m = message.orEmpty().lowercase()
        if (m.isBlank()) return UiSdkFailure(SdkFailureKind.UNKNOWN)
        if ("cancelled" in m || "canceled" in m) return UiSdkFailure(SdkFailureKind.CANCELLED)
        if ("license" in m) return UiSdkFailure(SdkFailureKind.LICENSE)
        if (STORAGE_MARKERS.any { it in m }) return UiSdkFailure(SdkFailureKind.STORAGE_FULL)
        HTTP_CODE.find(m)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 400 }?.let { code ->
            val kind = if (code == 404 || code == 410) SdkFailureKind.NOT_FOUND else SdkFailureKind.SERVER
            return UiSdkFailure(kind, code)
        }
        if ("not found in the repository" in m) return UiSdkFailure(SdkFailureKind.NOT_FOUND)
        // HttpURLConnection reports a 404 as a FileNotFoundException carrying the URL.
        if ("filenotfoundexception" in m && "http" in m) return UiSdkFailure(SdkFailureKind.NOT_FOUND, 404)
        if (OFFLINE_MARKERS.any { it in m }) return UiSdkFailure(SdkFailureKind.OFFLINE)
        if (CORRUPT_MARKERS.any { it in m }) return UiSdkFailure(SdkFailureKind.CORRUPT)
        return UiSdkFailure(SdkFailureKind.UNKNOWN)
    }
}
