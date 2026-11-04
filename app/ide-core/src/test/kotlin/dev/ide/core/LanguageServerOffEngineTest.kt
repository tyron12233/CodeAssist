package dev.ide.core

import dev.ide.lang.FILE_TYPE_EP
import dev.ide.lang.FileTypeMapping
import dev.ide.lang.LanguageId
import dev.ide.lsp.LANGUAGE_SERVER_EP
import dev.ide.lsp.LanguageServerConnection
import dev.ide.lsp.LanguageServerContext
import dev.ide.lsp.LanguageServerDefinition
import dev.ide.lsp.client.JsonRpcConnection
import dev.ide.platform.PluginId
import dev.ide.platform.log.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A slow language server does not hold the engine thread. The editor does each LSP pass's round trip off the
 * engine first, so Java work queued meanwhile runs at once, and the engine's own call is a cache lookup (the
 * server is asked once, not twice).
 */
class LanguageServerOffEngineTest {

    private val root = createTempDirectory("lsp-off-engine")
    private var services: IdeServices? = null
    private val semanticRequests = AtomicInteger()
    @Volatile private var serverDelayMs = SERVER_DELAY_MS

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    /** Answers semantic tokens after [SERVER_DELAY_MS]: a server busy analysing. */
    private val slow = object : LanguageServerDefinition {
        override val id = "slow"
        override val languages = setOf(LanguageId("toy"))
        override fun start(context: LanguageServerContext): LanguageServerConnection {
            val clientOut = PipedOutputStream()
            val serverIn = PipedInputStream(clientOut, 1 shl 16)
            val serverOut = PipedOutputStream()
            val clientIn = PipedInputStream(serverOut, 1 shl 16)
            val c = JsonRpcConnection(serverIn, serverOut, "slow-server", Log.logger("slow-server"))
            c.requestHandler = JsonRpcConnection.RequestHandler { method, _ ->
                when (method) {
                    "initialize" -> buildJsonObject {
                        putJsonObject("capabilities") {
                            put("textDocumentSync", 1)
                            putJsonObject("semanticTokensProvider") {
                                putJsonObject("legend") {
                                    putJsonArray("tokenTypes") { add("keyword") }
                                    putJsonArray("tokenModifiers") {}
                                }
                                put("full", true)
                            }
                        }
                    }
                    "textDocument/semanticTokens/full" -> {
                        semanticRequests.incrementAndGet()
                        Thread.sleep(serverDelayMs)
                        buildJsonObject { putJsonArray("data") { add(0); add(0); add(3); add(0); add(0) } }
                    }
                    else -> null
                }
            }
            c.start()
            return LanguageServerConnection(clientIn, clientOut, null) { c.close() }
        }
    }

    private fun boot(): Pair<IdeServices, IdeServicesBackend> {
        val env = ApplicationEnvironment()
        val plugin = PluginId("test-slow")
        env.platform.extensions.register(FILE_TYPE_EP, FileTypeMapping(listOf(".toy"), LanguageId("toy")), plugin)
        env.platform.extensions.register(LANGUAGE_SERVER_EP, slow, plugin)
        env.languageServers.registerAll()
        val s = IdeServices.bootstrapJavaDemo(root, env).also { services = it }
        return s to IdeServicesBackend(s)
    }

    /**
     * A Dart package or a Python script sits in no source root. The editor's passes are gated on a module, and
     * such a file used to get the server asked (by the ungated prefetch) and nothing shown.
     */
    @Test
    fun aServedFileOutsideEverySourceRootIsStillAnalysed() {
        serverDelayMs = 0
        val (s, backend) = boot()
        val toy = root.resolve("tools/scripts/outside.toy")
        Files.createDirectories(toy.parent)
        Files.writeString(toy, "fun x")
        s.modules()
        runBlocking {
            assertEquals(1, backend.editor.semanticTokens(toy.toString(), "fun x").size)
        }
        // Only a language the server is the backend for: a plain-text file there is still in no module.
        assertEquals(null, s.moduleForFile(root.resolve("tools/scripts/notes.txt")))
        assertEquals(null, s.moduleForFile(root.resolve("tools/scripts/Stray.java")))
    }

    @Test
    fun aSlowServerDoesNotHoldTheEngine() {
        val (s, backend) = boot()

        val toy = root.resolve("app/src/main/java/com/example/app/slow.toy")
        Files.createDirectories(toy.parent)
        Files.writeString(toy, "fun x")
        s.modules()
        val java = Files.walk(root).use { w -> w.filter { it.isRegularFile() && it.toString().endsWith(".java") }.findFirst().get() }
        val javaText = java.readText()

        runBlocking {
            // Warm both paths, so neither number below is a first-call cost.
            backend.editor.codeFolds(java.toString(), javaText)
            backend.editor.semanticTokens(toy.toString(), "fun")
            semanticRequests.set(0)

            val toyPass = async { backend.editor.semanticTokens(toy.toString(), "fun x") }
            delay(150) // the toy pass is now waiting on the server
            val started = System.nanoTime()
            backend.editor.codeFolds(java.toString(), javaText)
            val javaMillis = (System.nanoTime() - started) / 1_000_000

            val tokens = toyPass.await()
            assertEquals(1, tokens.size, "the toy pass still gets the server's answer")
            assertTrue(
                javaMillis < SERVER_DELAY_MS / 2,
                "the Java pass waited ${javaMillis}ms: the engine was held while the server worked",
            )
            assertEquals(1, semanticRequests.get(), "the engine's call found the prefetched answer")
        }
    }

    private companion object {
        const val SERVER_DELAY_MS = 800L
    }
}
