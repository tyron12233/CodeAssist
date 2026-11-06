package dev.ide.lsp.client

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * File events go only to a server that registered for them. Reported: the Dart analysis server, which watches the
 * disk itself, answered every unasked-for `workspace/didChangeWatchedFiles` with an "Unknown method" error toast.
 */
class LspWatchedFilesTest {
    private val root: Path = Files.createTempDirectory("lsp-watched").toRealPath()
    private val server = FakeLanguageServer()
    private val manager = LspSessionManager(object : LspSessionManager.Host {
        override fun projectRootFor(file: Path): Path = root
    })

    @AfterTest
    fun tearDown() {
        manager.close()
        root.toFile().deleteRecursively()
    }

    private fun watchedCount() = server.received.count { it == LspSession.DID_CHANGE_WATCHED_FILES }

    @Test
    fun fileEventsWaitForTheServerToAskForThem() {
        val file = root.resolve("a.toy")
        val session = assertNotNull(manager.sessionFor(server.definition, file))
        assertTrue(session.awaitReady(10_000))

        manager.filesChanged(listOf(file to 2))

        // The server asks for file events; the client answers the registration before anything after it.
        server.connection!!.request("client/registerCapability", buildJsonObject {
            putJsonArray("registrations") {
                addJsonObject { put("id", "w1"); put("method", LspSession.DID_CHANGE_WATCHED_FILES) }
            }
        }).future.get(10, TimeUnit.SECONDS)

        manager.filesChanged(listOf(file to 2))
        // A request round trip as a fence: the pipe and the server's dispatch are ordered, so once it is
        // answered, every notification sent before it has been received.
        session.requestOrThrow("textDocument/hover", null, 10_000)

        assertEquals(1, watchedCount(), "only the event after the registration: ${server.received}")
    }
}
