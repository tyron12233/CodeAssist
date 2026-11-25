package dev.ide.core

import dev.ide.index.Externalizer
import dev.ide.index.INDEX_EP
import dev.ide.index.INDEX_QUERIES
import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexQueries
import dev.ide.index.IndexService
import dev.ide.index.InputFilter
import dev.ide.index.MatchingMode
import dev.ide.index.StringKeyDescriptor
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.platform.DataReader
import dev.ide.platform.DataWriter
import dev.ide.platform.PluginId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An index a plugin contributes, end to end through the real [IdeServices]: registered on [INDEX_EP], fed files
 * of the plugin's own type (which the IDE does not otherwise read), kept current on save and on create, and
 * queried back through the published [INDEX_QUERIES] key the way a plugin's action would.
 */
class PluginIndexTest {

    /** One greeting found in a `.greet` file: which file, and on which line. */
    data class Greeting(val fileId: Int, val line: Int)

    /** Indexes every `hello <name>` line of a `.greet` file under `<name>`. A plugin's index, in shape. */
    private class GreetingIndex : IndexExtension<String, Greeting> {
        override val id = ID
        override val version = 1
        override val keyDescriptor = StringKeyDescriptor
        override val valueExternalizer = object : Externalizer<Greeting> {
            override fun write(out: DataWriter, value: Greeting) {
                out.writeInt(value.fileId); out.writeInt(value.line)
            }

            override fun read(inp: DataReader) = Greeting(inp.readInt(), inp.readInt())
        }
        override val inputFilter = InputFilter { it.origin == IndexOrigin.SOURCE && it.unitName?.endsWith(".greet") == true }
        override val matching = MatchingMode.PREFIX_ONLY

        // Without the dot, to check the host normalizes it.
        override val sourceFileExtensions = setOf("greet")

        override fun index(input: IndexInput): Map<String, Collection<Greeting>> {
            val out = HashMap<String, MutableList<Greeting>>()
            input.text()?.lineSequence()?.forEachIndexed { line, text ->
                val name = text.trim().removePrefix("hello ").takeIf { it != text.trim() }?.trim() ?: return@forEachIndexed
                out.getOrPut(name) { ArrayList() }.add(Greeting(input.fileId, line))
            }
            return out
        }

        companion object {
            val ID = IndexId("test.greetings")
        }
    }

    private val root = createTempDirectory("plugin-index")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    private fun bootstrap(): IdeServices {
        val env = ApplicationEnvironment()
        env.platform.extensions.register(INDEX_EP, GreetingIndex(), PluginId("test-greetings"))
        return IdeServices.bootstrapJavaDemo(root, env).also { services = it }
    }

    private fun sourceDir(): Path = root.resolve("app/src/main/java/com/example/app")

    private fun IdeServices.queries(): IndexQueries = store.workspace.service(INDEX_QUERIES)

    /** Poll [query] until it answers non-empty: indexing and the save path both run in the background. */
    private fun <T> awaitNonEmpty(timeoutMs: Long = 120_000, query: () -> List<T>): List<T> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val hits = query()
            if (hits.isNotEmpty()) return hits
            Thread.sleep(50)
        }
        return query()
    }

    @Test
    fun aPluginsIndexReadsItsOwnFileTypeAndIsQueryableThroughTheSpiKey() {
        val ide = bootstrap()
        val greet = sourceDir().resolve("people.greet")
        Files.createDirectories(greet.parent)
        greet.writeText("hello world\nnot a greeting\nhello Ada\n")

        // The file was not there when the project opened, so a full pass has to find it on the walk.
        ide.reindex()

        val queries = ide.queries()
        val hits = awaitNonEmpty { queries.exact<Greeting>(GreetingIndex.ID, "world").toList() }
        assertEquals(listOf(0), hits.map { it.line }, "the walk read the .greet file")
        assertEquals(
            greet.toAbsolutePath().normalize().toString(),
            queries.filePath(hits.single().fileId)?.let { Path.of(it).toAbsolutePath().normalize().toString() },
            "a value's file id resolves back to the file",
        )
        assertEquals(listOf(2), queries.exact<Greeting>(GreetingIndex.ID, "Ada").map { it.line }.toList())
        assertEquals(listOf("Ada"), queries.prefix<Greeting>(GreetingIndex.ID, "A").map { it.key }.toList())
        // Entries are queryable mid-pass; the pass that found them reports ready once it ends.
        assertTrue(
            awaitNonEmpty { listOfNotNull(queries.status.takeIf { it.ready }) }.isNotEmpty(),
            "the status is readable through the key: ${queries.status}",
        )
    }

    @Test
    fun aSaveAndACreateKeepAPluginsIndexCurrent() {
        val ide = bootstrap()
        val queries = ide.queries()
        // Let the open pass finish first, so a later answer can only have come from the save or create path.
        awaitNonEmpty { listOfNotNull(queries.status.takeIf { it.ready }) }

        val greet = sourceDir().resolve("later.greet")
        Files.createDirectories(greet.parent)
        greet.writeText("hello Grace\n")
        ide.events.fileCreated(greet)
        assertEquals(
            listOf(0),
            awaitNonEmpty { queries.exact<Greeting>(GreetingIndex.ID, "Grace").toList() }.map { it.line },
            "a created file is added to the index",
        )

        ide.save(greet, "// edited\nhello Linus\n")
        assertEquals(
            listOf(1),
            awaitNonEmpty { queries.exact<Greeting>(GreetingIndex.ID, "Linus").toList() }.map { it.line },
            "a saved file is re-indexed",
        )
        assertTrue(queries.exact<Greeting>(GreetingIndex.ID, "Grace").none(), "its old entries are gone")
    }

    /** A plugin can ask, but not rebuild or drop the cache: the key resolves a view, not the service. */
    @Test
    fun theKeyIsQueryOnly() {
        val ide = bootstrap()
        assertFalse(ide.queries() is IndexService, "INDEX_QUERIES must not hand out the IndexService itself")
    }

    @Test
    fun suffixesAreNormalizedAndTheBuiltInOnesDropped() {
        val ext = object : IndexExtension<String, String> by StubIndex() {
            override val sourceFileExtensions = setOf("swift", ".dart", " .proto ", ".java", "", ".")
        }
        assertEquals(setOf(".swift", ".dart", ".proto"), IndexServiceImpl.sourceSuffixesOf(listOf(ext)))
    }

    private class StubIndex : IndexExtension<String, String> {
        override val id = IndexId("test.stub")
        override val version = 1
        override val keyDescriptor = StringKeyDescriptor
        override val valueExternalizer = object : Externalizer<String> {
            override fun write(out: DataWriter, value: String) = out.writeUTF(value)
            override fun read(inp: DataReader) = inp.readUTF()
        }
        override val inputFilter = InputFilter { false }
        override val matching = MatchingMode.PREFIX_ONLY
        override fun index(input: IndexInput): Map<String, Collection<String>> = emptyMap()
    }
}
