package dev.ide.android.support.tasks

import dev.ide.build.TaskName
import dev.ide.build.engine.SimpleTaskContext
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `mergeResources` deduplicates a value resource that arrives from more than one source (the device failure
 * mode: the same Compose/AndroidX library reached through two cache paths, so a string like `tooltip_label`
 * was defined twice and `aapt2 link` rejected it). Ascending priority means the last source wins. No SDK
 * needed — this exercises the merge logic directly.
 */
class MergeResourcesTaskTest {

    private fun writeValues(dir: Path, qualifier: String, body: String): Path {
        val f = dir.resolve(qualifier).resolve("strings.xml")
        Files.createDirectories(f.parent)
        Files.writeString(f, "<resources>\n$body\n</resources>")
        return dir
    }

    @Test
    fun duplicateValueAcrossSourcesCollapsesToOneLastWins() = runBlocking {
        val tmp = Files.createTempDirectory("merge-res")
        try {
            // Two sources both define string/app_name + string/tooltip_label for the same (default) config —
            // exactly the shape that made aapt2 link fail. low/ is lower priority than high/.
            val low = writeValues(tmp.resolve("low"), "values",
                "<string name=\"app_name\">Low</string>\n<string name=\"tooltip_label\">L</string>")
            val high = writeValues(tmp.resolve("high"), "values",
                "<string name=\"app_name\">High</string>\n<string name=\"only_high\">H</string>")
            val out = tmp.resolve("merged")

            MergeResourcesTask(TaskName(":app:mergeResources"), listOf(low, high), out)
                .execute(SimpleTaskContext())

            val merged = out.resolve("values").resolve("values.xml")
            assertTrue(Files.exists(merged), "expected a single merged values.xml")
            // No stray per-source copies that would re-introduce the duplicate at aapt2 link.
            val valuesFiles = Files.list(out.resolve("values")).use { it.toList() }
            assertEquals(listOf("values.xml"), valuesFiles.map { it.fileName.toString() }.sorted())

            val text = Files.readString(merged)
            assertEquals(1, Regex("name=\"app_name\"").findAll(text).count(), "app_name must appear once")
            assertTrue(text.contains("High"), "higher-priority source must win: $text")
            assertTrue(!text.contains(">Low<"), "lower-priority value must be overridden: $text")
            assertTrue(text.contains("tooltip_label"), "non-overlapping low entry kept")
            assertTrue(text.contains("only_high"), "non-overlapping high entry kept")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
