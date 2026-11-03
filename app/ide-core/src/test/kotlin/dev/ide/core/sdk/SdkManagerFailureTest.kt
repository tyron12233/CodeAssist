package dev.ide.core.sdk

import dev.ide.android.support.tools.SdkNetFetcher
import dev.ide.ui.backend.SdkFailureKind
import dev.ide.ui.backend.UiSdkDownload
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** A failed SDK Manager download keeps its cause: the raw text for Details, and a classification for the UI. */
class SdkManagerFailureTest {

    private fun awaitSettled(service: SdkManagerService, id: String): UiSdkDownload {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            service.state.value.downloads.firstOrNull { it.id == id && (it.status == "FAILED" || it.status == "DONE") }
                ?.let { return it }
            Thread.sleep(20)
        }
        fail("download $id never settled: ${service.state.value.downloads}")
    }

    @Test
    fun anOfflineFailureIsClassifiedAndKeepsTheRawError() {
        val root = Files.createTempDirectory("sdkfail")
        val offline = object : SdkNetFetcher {
            override fun fetchText(url: String): String? = null
            override fun download(url: String, dest: Path, onProgress: (Long, Long) -> Unit): Boolean =
                throw java.net.UnknownHostException("api.adoptium.net")
        }
        val service = SdkManagerService(root, offline)
        try {
            service.downloadJdkSources(21)
            val d = awaitSettled(service, "jdk-21")
            assertEquals("FAILED", d.status)
            assertTrue("UnknownHostException" in d.detail, d.detail)
            assertEquals(SdkFailureKind.OFFLINE, d.failure?.kind)
            // Nothing is in flight any more, so the aggregate line is clear rather than still "Downloading".
            assertEquals("", service.state.value.message)
            assertEquals(false, service.state.value.busy)
        } finally {
            service.dispose()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun aRetryClearsTheEarlierFailure() {
        val root = Files.createTempDirectory("sdkretry")
        var attempts = 0
        val flaky = object : SdkNetFetcher {
            override fun fetchText(url: String): String? = null
            override fun download(url: String, dest: Path, onProgress: (Long, Long) -> Unit): Boolean {
                attempts++
                // Block the second attempt until the test has looked at the in-flight entry.
                if (attempts == 2) Thread.sleep(300)
                throw java.io.IOException("No space left on device")
            }
        }
        val service = SdkManagerService(root, flaky)
        try {
            service.downloadJdkSources(17)
            assertEquals(SdkFailureKind.STORAGE_FULL, awaitSettled(service, "jdk-17").failure?.kind)
            // The finished job unregisters just after it settles; until then a start is refused as a duplicate.
            val deadline = System.currentTimeMillis() + 5_000
            while (service.downloadJdkSources(17).startsWith("Already") && System.currentTimeMillis() < deadline) Thread.sleep(10)
            val inFlight = service.state.value.downloads.first { it.id == "jdk-17" }
            assertEquals("DOWNLOADING", inFlight.status)
            assertEquals(null, inFlight.failure)
        } finally {
            service.dispose()
            root.toFile().deleteRecursively()
        }
    }
}
