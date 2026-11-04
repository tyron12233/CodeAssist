package dev.ide.lsp.client

import dev.ide.lang.LanguageId
import dev.ide.lsp.LanguageServerConnection
import dev.ide.lsp.LanguageServerContext
import dev.ide.lsp.LanguageServerDefinition
import dev.ide.platform.log.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Restarts that tell a crash loop from a server Android killed, and the per-request timings. */
class LspResilienceTest {
    private val root: Path = Files.createTempDirectory("lsp-resilience").toRealPath()
    private val now = AtomicLong(1_000_000)
    private val messages = CopyOnWriteArrayList<String>()
    private val starts = AtomicInteger()
    private var manager: LspSessionManager? = null

    @AfterTest
    fun tearDown() {
        manager?.close()
        root.toFile().deleteRecursively()
    }

    /** A server that answers `initialize`, then dies: what a crash (or the phantom process killer) looks like. */
    private val dying = object : LanguageServerDefinition {
        override val id = "dying"
        override val languages = setOf(LanguageId("toy"))
        override fun start(context: LanguageServerContext): LanguageServerConnection {
            starts.incrementAndGet()
            val clientOut = PipedOutputStream()
            val serverIn = PipedInputStream(clientOut, 1 shl 16)
            val serverOut = PipedOutputStream()
            val clientIn = PipedInputStream(serverOut, 1 shl 16)
            val server = JsonRpcConnection(serverIn, serverOut, "dying", Log.logger("dying"))
            server.requestHandler = JsonRpcConnection.RequestHandler { method, _ ->
                if (method == "initialize") {
                    Thread { Thread.sleep(50); runCatching { serverOut.close() } }.start()
                    buildJsonObject { putJsonObject("capabilities") {} }
                } else null
            }
            server.start()
            return LanguageServerConnection(clientIn, clientOut, null) { server.close() }
        }
    }

    private fun manager(): LspSessionManager = LspSessionManager(object : LspSessionManager.Host {
        override fun projectRootFor(file: Path): Path = root
        override fun message(definition: LanguageServerDefinition, type: Int, text: String) { messages += text }
    }, clock = { now.get() }).also { manager = it }

    /** Ask for the session and wait until it has started and died. */
    private fun startAndLoseIt(m: LspSessionManager, file: Path): LspSession? {
        val s = m.sessionFor(dying, file) ?: return null
        val deadline = System.currentTimeMillis() + 5_000
        while (s.state != LspSession.State.FAILED && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(LspSession.State.FAILED, s.state)
        return s
    }

    @Test
    fun aCrashLoopIsLeftDownAndTheUserIsTold() {
        val m = manager()
        val file = root.resolve("a.toy")
        repeat(LspSessionManager.MAX_RESTARTS + 1) { assertNotNull(startAndLoseIt(m, file), "attempt $it") }
        // One failure more than the cap within the window: down, and said so once.
        assertNull(m.sessionFor(dying, file))
        assertNull(m.sessionFor(dying, file))
        assertEquals(1, messages.size, messages.toString())
        assertTrue("keeps failing" in messages.single())
    }

    @Test
    fun aServerThatDiesNowAndThenAlwaysComesBack() {
        val m = manager()
        val file = root.resolve("a.toy")
        // Killed once every ten minutes, far apart: never mistaken for a crash loop.
        repeat(LspSessionManager.MAX_RESTARTS * 3) {
            assertNotNull(startAndLoseIt(m, file), "attempt $it")
            now.addAndGet(10 * 60_000L)
        }
        assertNotNull(m.sessionFor(dying, file))
        assertTrue(messages.isEmpty(), messages.toString())
    }

    @Test
    fun requestsAreTimedPerMethod() {
        val server = FakeLanguageServer()
        val m = LspSessionManager(object : LspSessionManager.Host {
            override fun projectRootFor(file: Path): Path = root
        }).also { manager = it }
        val file = root.resolve("t.toy")
        val session = m.sessionFor(server.definition, file)!!
        assertTrue(session.awaitReady(5_000))
        session.sync(file, "fun greet() {}\ngreet()")
        repeat(3) {
            session.request("textDocument/hover", buildJsonObject {
                put("textDocument", textDocument(uriOf(file)))
                put("position", LineIndex.position(1, 2))
            }, 5_000)
        }
        val hover = session.stats().getValue("textDocument/hover")
        assertEquals(3, hover.count)
        assertTrue(hover.totalChars > 0, hover.toString())
        assertEquals(0, hover.timeouts)
        assertTrue("n=3" in hover.toString(), hover.toString())
    }

    @Test
    fun aFileWhoseFolderIsGoneStartsNoServer() {
        val m = manager()
        // A tab still open on a file that was moved away with its folder.
        assertNull(m.sessionFor(dying, root.resolve("moved/away/a.toy")))
        assertEquals(0, starts.get())
        assertTrue(m.sessions().isEmpty())
    }
}
