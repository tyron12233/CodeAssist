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

    /**
     * Two libraries declaring the same-named `<declare-styleable>` with DISJOINT attrs must have their
     * child `<attr>` declarations UNIONED, not last-wins-overridden (aapt2 semantics). The device failure:
     * AppCompat and Material both ship `<declare-styleable name="SearchView">`; overriding dropped Material's
     * `dividerVisible`/`containedAnimationEnabled` (declared ONLY there), so a style referencing them failed
     * `aapt2 link` with "style attribute 'attr/dividerVisible' not found".
     */
    @Test
    fun sameNamedStyleableUnionsChildAttrs() = runBlocking {
        val tmp = Files.createTempDirectory("merge-styleable")
        try {
            // AppCompat-shaped SearchView: bare references + its own attrs.
            val appcompat = writeValues(tmp.resolve("appcompat"), "values",
                "<declare-styleable name=\"SearchView\">" +
                    "<attr format=\"reference\" name=\"layout\"/>" +
                    "<attr format=\"boolean\" name=\"iconifiedByDefault\"/>" +
                    "</declare-styleable>")
            // Material-shaped SearchView: the attrs declared ONLY here (format-bearing).
            val material = writeValues(tmp.resolve("material"), "values",
                "<declare-styleable name=\"SearchView\">" +
                    "<attr format=\"reference\" name=\"layout\"/>" +
                    "<attr format=\"boolean\" name=\"dividerVisible\"/>" +
                    "<attr format=\"boolean\" name=\"containedAnimationEnabled\"/>" +
                    "</declare-styleable>")
            val out = tmp.resolve("merged")

            MergeResourcesTask(TaskName(":app:mergeResources"), listOf(appcompat, material), out)
                .execute(SimpleTaskContext())

            val text = Files.readString(out.resolve("values").resolve("values.xml"))
            // Exactly one SearchView styleable survives, holding the UNION of both sources' attrs.
            assertEquals(1, Regex("declare-styleable name=\"SearchView\"").findAll(text).count(),
                "one merged SearchView styleable")
            assertTrue(Regex("name=\"dividerVisible\"[^>]*format|format=[^>]*name=\"dividerVisible\"|format=\"boolean\" name=\"dividerVisible\"")
                .containsMatchIn(text) || text.contains("name=\"dividerVisible\""),
                "Material's dividerVisible attr must survive the union: $text")
            assertTrue(text.contains("name=\"containedAnimationEnabled\""),
                "Material's containedAnimationEnabled attr must survive the union")
            assertTrue(text.contains("name=\"iconifiedByDefault\""),
                "AppCompat's iconifiedByDefault attr must survive the union")
            // `layout` is shared by both sources but must appear exactly once after dedup.
            assertEquals(1, Regex("name=\"layout\"").findAll(text).count(),
                "shared attr deduped to one declaration")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
