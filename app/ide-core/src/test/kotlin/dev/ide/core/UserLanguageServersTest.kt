package dev.ide.core

import dev.ide.analysis.DIAGNOSTIC_PROVIDER_EP
import dev.ide.core.lsp.UserLanguageServer
import dev.ide.core.lsp.UserLanguageServers
import dev.ide.lang.dom.Severity
import dev.ide.lsp.LANGUAGE_SERVER_EP
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Language servers configured in Settings: persisted, validated, registered and taken back. */
class UserLanguageServersTest {

    private val root = createTempDirectory("user-lsp")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    @Test
    fun savesValidatesAndSurvivesARestart() {
        val env = ApplicationEnvironment()
        val file = root.resolve("language-servers.json")
        val servers = UserLanguageServers(env, file)

        assertEquals("Give the server a name.", servers.save(UserLanguageServer("", " ", "gopls", listOf(".go"), "go")))
        assertEquals("List at least one file extension, such as .go.", servers.save(UserLanguageServer("", "Go", "gopls", emptyList(), "go")))
        assertEquals("Initialization options must be JSON.", servers.save(UserLanguageServer("", "Go", "gopls", listOf("go"), "go", initializationOptions = "{nope")))

        assertNull(servers.save(UserLanguageServer("", "Go (gopls)", "gopls", listOf("go", "*.MOD"), "Go")))
        val saved = servers.list().single()
        assertEquals("go-gopls", saved.id)
        assertEquals(listOf(".go", ".mod"), saved.extensions)
        assertEquals("go", saved.languageId)

        // Two enabled servers may not claim one extension.
        assertEquals(".go is already served by Go (gopls).", servers.save(UserLanguageServer("", "Other", "x", listOf(".go"), "go2")))

        // The file is what the next launch reads.
        val reloaded = UserLanguageServers(ApplicationEnvironment(), file)
        assertEquals(listOf(saved), reloaded.list())
    }

    @Test
    fun registersOnTheRegistryAndTakesItBack() {
        val env = ApplicationEnvironment()
        val servers = UserLanguageServers(env, root.resolve("ls.json"))
        assertNull(servers.save(UserLanguageServer("", "Toy", "toy-server", listOf(".toy"), "toy")))
        val id = servers.list().single().id

        val providers = { env.platform.extensions.extensions(DIAGNOSTIC_PROVIDER_EP).map { it.id } }
        assertTrue(env.platform.extensions.extensions(LANGUAGE_SERVER_EP).any { it.id == UserLanguageServers.definitionId(id) })
        assertTrue("lsp:user:$id" in providers(), providers().toString())

        servers.delete(id)
        assertTrue(env.platform.extensions.extensions(LANGUAGE_SERVER_EP).none { it.id == UserLanguageServers.definitionId(id) })
        assertTrue("lsp:user:$id" !in providers(), "the adapters are disposed with the definition: ${providers()}")
    }

    @Test
    fun splitsCommandsAndResolvesExecutables() {
        assertEquals(listOf("pyright-langserver", "--stdio"), UserLanguageServers.splitCommand("pyright-langserver  --stdio"))
        assertEquals(listOf("/opt/my tools/srv", "--log", ""), UserLanguageServers.splitCommand("\"/opt/my tools/srv\" --log ''"))
        assertEquals(".go", UserLanguageServers.normalizeExtension(" *.GO "))
        assertNull(UserLanguageServers.normalizeExtension("  "))
        // `sh` exists on every machine this suite runs on, and is found through PATH.
        assertNotNull(UserLanguageServers.resolveExecutable("sh"))
        assertNull(UserLanguageServers.resolveExecutable("surely-not-an-installed-server-xyz"))
    }

    /** A user-configured clangd serves .cpp files end to end, where one is installed. */
    @Test
    fun aConfiguredServerServesItsFiles() {
        assumeTrue(Files.isExecutable(Paths.get("/usr/bin/clangd")) || UserLanguageServers.resolveExecutable("clangd") != null, "no clangd")
        val env = ApplicationEnvironment()
        val servers = UserLanguageServers(env, root.resolve("ls.json"))
        assertNull(servers.save(UserLanguageServer("", "clangd", "clangd --log=error --background-index=false", listOf(".cpp"), "cpp")))
        val s = IdeServices.bootstrapJavaDemo(root.resolve("project"), env).also { services = it }

        val text = "int main() {\n  zqxw;\n  return 0;\n}\n"
        val file = root.resolve("project/app/src/main/java/com/example/app/main.cpp")
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        s.modules()

        val deadline = System.currentTimeMillis() + 30_000
        var errors = emptyList<dev.ide.analysis.Diagnostic>()
        while (errors.isEmpty() && System.currentTimeMillis() < deadline) {
            errors = runBlocking { s.analyzeDiagnostics(file, text) }.filter { it.severity == Severity.ERROR }
            if (errors.isEmpty()) Thread.sleep(200)
        }
        assertEquals("zqxw", errors.firstOrNull()?.let { text.substring(it.range.start, it.range.end) }, errors.toString())
        assertEquals("Running", servers.status(servers.list().single().id))
    }
}
