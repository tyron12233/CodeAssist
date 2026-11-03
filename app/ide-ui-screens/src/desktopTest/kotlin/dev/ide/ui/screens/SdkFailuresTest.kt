package dev.ide.ui.screens

import dev.ide.ui.backend.SdkFailureKind
import dev.ide.ui.backend.SdkFailures
import kotlin.test.Test
import kotlin.test.assertEquals

/** The SDK Manager's failure classifier, over the texts the download path actually produces. */
class SdkFailuresTest {

    private fun kind(message: String?) = SdkFailures.classify(message).kind

    @Test
    fun noConnectionIsOffline() {
        assertEquals(SdkFailureKind.OFFLINE, kind("Install failed: UnknownHostException: dl.google.com"))
        assertEquals(SdkFailureKind.OFFLINE, kind("Download failed: ConnectException: Failed to connect to /142.250.1.1:443"))
        assertEquals(SdkFailureKind.OFFLINE, kind("Install failed: SocketTimeoutException: Read timed out"))
        assertEquals(
            SdkFailureKind.OFFLINE,
            kind("Download failed: IOException: Could not reach the Android SDK repository (https://dl.google.com/android/repository/repository2-3.xml). Check your internet connection."),
        )
        assertEquals(SdkFailureKind.OFFLINE, kind("Install failed: SSLHandshakeException: connection closed"))
    }

    @Test
    fun aFullDiskIsStorage() {
        assertEquals(SdkFailureKind.STORAGE_FULL, kind("Install failed: IOException: No space left on device"))
        assertEquals(SdkFailureKind.STORAGE_FULL, kind("Install failed: FileSystemException: write failed: ENOSPC (No space left on device)"))
    }

    @Test
    fun httpStatusesSplitIntoNotFoundAndServer() {
        val gone = SdkFailures.classify("Install failed: SdkHttpException: HTTP 404 from https://dl.google.com/android/repository/platform-25_r03.zip")
        assertEquals(SdkFailureKind.NOT_FOUND, gone.kind)
        assertEquals(404, gone.httpCode)
        val down = SdkFailures.classify("Install failed: SdkHttpException: HTTP 503 from https://dl.google.com/x.zip")
        assertEquals(SdkFailureKind.SERVER, down.kind)
        assertEquals(503, down.httpCode)
        assertEquals(SdkFailureKind.SERVER, kind("IOException: Server returned HTTP response code: 500 for URL: https://x"))
        assertEquals(SdkFailureKind.NOT_FOUND, kind("Package platforms;android-99 not found in the repository."))
        assertEquals(SdkFailureKind.NOT_FOUND, kind("Download failed: FileNotFoundException: https://dl.google.com/gone.zip"))
    }

    @Test
    fun cancelLicenseAndDamage() {
        assertEquals(SdkFailureKind.CANCELLED, kind("Cancelled"))
        assertEquals(SdkFailureKind.LICENSE, kind("Accept the license for Android SDK Platform 35 first."))
        assertEquals(SdkFailureKind.CORRUPT, kind("Downloaded archive was incomplete; retry to re-download. (ZipException: invalid END header)"))
        assertEquals(SdkFailureKind.CORRUPT, kind("Install failed: EOFException: Unexpected end of ZLIB input stream"))
    }

    @Test
    fun anythingElseIsUnknown() {
        assertEquals(SdkFailureKind.UNKNOWN, kind("Download failed."))
        assertEquals(SdkFailureKind.UNKNOWN, kind(""))
        assertEquals(SdkFailureKind.UNKNOWN, kind(null))
        // A 2xx/3xx number in the text is not an HTTP error.
        assertEquals(SdkFailureKind.UNKNOWN, kind("Install failed: IllegalStateException: HTTP 206 without a range"))
    }
}
