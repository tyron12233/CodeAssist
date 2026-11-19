package dev.ide.ui

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A repeated key in a lazy list ends the measure pass with no frame of ours on the stack, so the crash row
 * cannot name the list. These hold the two halves of the guard: [distinctKeys] renames a repeat wherever in
 * the list it comes from, and the source itself gives keys only through the guarded calls.
 */
class LazyKeyGuardTest {

    /** The keys a list would be measured with, in order; `null` for an item with none. */
    private class RecordingScope : LazyListScope {
        val keys = mutableListOf<Any?>()

        override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
            keys += key
        }

        override fun items(
            count: Int,
            key: ((index: Int) -> Any)?,
            contentType: (index: Int) -> Any?,
            itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
        ) {
            repeat(count) { keys += key?.invoke(it) }
        }

        override fun stickyHeader(key: Any?, contentType: Any?, content: @Composable LazyItemScope.(Int) -> Unit) {
            keys += key
        }
    }

    @Test
    fun aKeyRepeatedBetweenTwoCallsIsRenamed() {
        val list = RecordingScope()
        // Two groups that hold the same function, the shape the per-call guard could not see.
        list.distinctKeys {
            item("h:a") {}
            itemsKeyed(listOf("f:run", "f:stop"), key = { it }) {}
            item("h:b") {}
            itemsKeyed(listOf("f:run"), key = { it }) {}
            item("h:a") {}
            stickyHeader("f:stop") {}
            items(2, key = { "f:run" }) {}
            item {}
            items(2) {}
        }
        val keyed = list.keys.filterNotNull()
        assertEquals(keyed.size, keyed.toSet().size, "no key reaches the list twice: $keyed")
        assertEquals(listOf<Any?>("h:a", "f:run", "f:stop", "h:b"), list.keys.take(4), "a first claim keeps its key")
        assertEquals(3, list.keys.count { it == null }, "an item with no key is left to its index")
    }

    @Test
    fun theSameContentGivesTheSameKeys() {
        fun keys() = RecordingScope().also { scope ->
            scope.distinctKeys {
                itemsKeyed(listOf("a", "a"), key = { it }) {}
                item("a") {}
            }
        }.keys
        assertEquals(keys(), keys(), "a renamed key is stable, so an item keeps its identity across recompositions")
    }

    @Test
    fun noListGivesKeysAroundTheGuard() {
        val root = repoRoot()
        val violations = mutableListOf<String>()
        var lists = 0
        for (file in uiSources(root)) {
            val text = file.readText()
            if ("Lazy" !in text) continue
            val where = { offset: Int -> "${file.relativeTo(root)}:${text.lineOf(offset)}" }

            if (file.name != "LazyKeys.kt") {
                for (call in RAW_ITEMS.findAll(text)) {
                    val args = text.substring(call.range.last + 1, text.closing(call.range.last + 1, '(', ')'))
                    if (KEY_ARGUMENT.containsMatchIn(args)) {
                        violations += "${where(call.range.first)}: `${call.groupValues[1]}(…, key = …)`: use itemsKeyed / itemsIndexedKeyed"
                    }
                }
            }

            for (list in LAZY_LIST.findAll(text)) {
                val afterArgs = text.closing(list.range.last + 1, '(', ')')
                val open = Regex("""\s*\{""").matchAt(text, afterArgs) ?: continue
                val body = text.substring(open.range.last + 1, text.closing(open.range.last + 1, '{', '}') - 1)
                lists++
                val keyedCalls = KEYED_CALL.findAll(body).count()
                val looped = "forEach" in body || "for (" in body
                if ((keyedCalls >= 2 || (keyedCalls == 1 && looped)) && "distinctKeys" !in body.take(40)) {
                    violations += "${where(list.range.first)}: keys from more than one call: wrap the content in distinctKeys { }"
                }
            }
        }
        assertTrue(lists > 50, "the scan found the lazy lists ($lists)")
        assertTrue(violations.isEmpty(), "lazy keys given around the guard:\n" + violations.joinToString("\n"))
    }

    private companion object {
        /** A bare `items(` or `itemsIndexed(`, the foundation calls, not a longer name ending in one. */
        val RAW_ITEMS = Regex("""(?<![A-Za-z0-9_.])(items|itemsIndexed)\(""")
        val KEY_ARGUMENT = Regex("""\bkey\s*=""")
        val LAZY_LIST = Regex("""(?<![A-Za-z0-9_.])(LazyColumn|LazyRow)\(""")

        /** One call that gives the list a key: a keyed `item`, a guarded `items`, or a helper that emits a section. */
        val KEYED_CALL = Regex(
            """(?<![A-Za-z0-9_.])(item|stickyHeader)\(\s*(key\s*=|"|[A-Za-z_][\w.]*\s*[,)])""" +
                """|(?<![A-Za-z0-9_.])(itemsKeyed|itemsIndexedKeyed)\(""" +
                """|(?<![A-Za-z0-9_])[a-z]\w*(Section|Grid|Rows|Items)\(""",
        )

        /** The offset just past the [close] that matches an [open] already consumed before [from]. */
        fun String.closing(from: Int, open: Char, close: Char): Int {
            var depth = 1
            var i = from
            while (i < length && depth > 0) {
                when (this[i]) {
                    open -> depth++
                    close -> depth--
                }
                i++
            }
            return i
        }

        fun String.lineOf(offset: Int): Int = 1 + substring(0, offset).count { it == '\n' }

        /** The UI's own main sources: the modules that can see the guard. A plugin written against the SPI cannot. */
        fun uiSources(root: File): Sequence<File> = sequenceOf("app", "services").flatMap { top ->
            File(root, top).walkTopDown()
                .onEnter { it.name != "build" && !it.name.startsWith(".") }
                .filter { it.isFile && it.extension == "kt" }
                .filter { file ->
                    val path = file.relativeTo(root).invariantSeparatorsPath
                    "/src/" in path && "Test/" !in path && "/test/" !in path
                }
        }

        fun repoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
            error("no settings.gradle.kts above ${System.getProperty("user.dir")}")
        }
    }
}
