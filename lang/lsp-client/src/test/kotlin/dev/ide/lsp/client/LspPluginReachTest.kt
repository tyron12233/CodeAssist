package dev.ide.lsp.client

import dev.ide.lsp.LanguageServerConnection
import dev.ide.lsp.LanguageServerContext
import dev.ide.lsp.LanguageServerDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** A plugin reaches its running server through [LanguageServerContext]: push a changed configuration, or restart. */
class LspPluginReachTest {
    private val root: Path = Files.createTempDirectory("lsp-reach").toRealPath()
    private val server = FakeLanguageServer()
    private val starts = AtomicInteger()
    @Volatile private var context: LanguageServerContext? = null
    @Volatile private var settings = """{"extraPaths":[]}"""

    private val definition = object : LanguageServerDefinition by server.definition {
        override fun start(context: LanguageServerContext): LanguageServerConnection? {
            starts.incrementAndGet()
            this@LspPluginReachTest.context = context
            return server.definition.start(context)
        }
        override fun configuration(section: String?, context: LanguageServerContext): String = settings
    }
    private val manager = LspSessionManager(object : LspSessionManager.Host {
        override fun projectRootFor(file: Path): Path = root
    })

    @AfterTest
    fun tearDown() {
        manager.close()
        root.toFile().deleteRecursively()
    }

    private fun count(method: String) = server.received.count { it == method }

    @Test
    fun notifyConfigurationChangedResendsTheConfiguration() {
        val session = assertNotNull(manager.sessionFor(definition, root.resolve("a.toy")))
        assertTrue(session.awaitReady(10_000))
        val before = count("workspace/didChangeConfiguration")

        settings = """{"extraPaths":["/site-packages"]}"""
        context!!.notifyConfigurationChanged()
        session.requestOrThrow("textDocument/hover", null, 10_000) // fence: earlier notifications are received

        assertEquals(before + 1, count("workspace/didChangeConfiguration"), "${server.received}")
    }

    @Test
    fun restartShutsTheServerDownAndTheNextRequestStartsAFreshOne() {
        val file = root.resolve("a.toy")
        val first = assertNotNull(manager.sessionFor(definition, file))
        assertTrue(first.awaitReady(10_000))

        context!!.restart()
        val deadline = System.currentTimeMillis() + 10_000
        while (first.state != LspSession.State.CLOSED && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(LspSession.State.CLOSED, first.state)

        val second = assertNotNull(manager.sessionFor(definition, file))
        assertNotSame(first, second)
        assertTrue(second.awaitReady(10_000))
        assertEquals(2, starts.get())
    }
}
