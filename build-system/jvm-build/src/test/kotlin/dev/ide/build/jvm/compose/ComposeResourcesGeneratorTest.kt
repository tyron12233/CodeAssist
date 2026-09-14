package dev.ide.build.jvm.compose

import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Compose resource generator, checked against the shape `components-resources` actually reads: the
 * accessors' byte ranges have to land on the right `.cvr` records, or a string resolves to another string's
 * bytes at runtime and nothing about the build looks wrong.
 */
class ComposeResourcesGeneratorTest {

    private fun seed(dir: Path): Path {
        val root = dir.resolve("src/commonMain/composeResources")
        fun write(rel: String, text: String) {
            val f = root.resolve(rel)
            Files.createDirectories(f.parent)
            f.writeText(text.trimIndent())
        }
        write("values/strings.xml", """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="projects">Projects</string>
                <string name="greeting">Hello &amp; welcome</string>
                <plurals name="item_count">
                    <item quantity="one">%1${'$'}d item</item>
                    <item quantity="other">%1${'$'}d items</item>
                </plurals>
                <string-array name="weekdays">
                    <item>Mon</item>
                    <item>Tue</item>
                </string-array>
            </resources>
        """)
        write("values-pt-rBR/strings.xml", """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="projects">Projetos</string>
            </resources>
        """)
        Files.createDirectories(root.resolve("drawable"))
        root.resolve("drawable/logo.png").writeText("not really a png")
        Files.createDirectories(root.resolve("font"))
        root.resolve("font/mono_regular.ttf").writeText("not really a font")
        return root
    }

    private fun generate(dir: Path, public: Boolean = true): Triple<Path, Path, ComposeResourcesGenerator.Result> {
        val root = seed(dir)
        val kotlinDir = dir.resolve("out/kotlin")
        val resourcesDir = dir.resolve("out/resources")
        val result = ComposeResourcesGenerator("com.example.generated.resources", public)
            .generate(listOf(root), kotlinDir, resourcesDir)
        return Triple(kotlinDir, resourcesDir, result)
    }

    @Test
    fun generatesAnAccessorPerResourceAndStagesTheFilesTheyName() {
        withTempDir("compose-res") { dir ->
            val (kotlinDir, resourcesDir, result) = generate(dir)

            // projects, greeting, item_count, weekdays, logo, mono_regular
            assertEquals(6, result.resourceCount)
            assertTrue(result.warnings.isEmpty(), "unexpected warnings: ${result.warnings}")

            val pkgDir = kotlinDir.resolve("com/example/generated/resources")
            val res = pkgDir.resolve("Res.kt").readText()
            assertContains(res, "public object Res")
            assertContains(res, "composeResources/com.example.generated.resources/")
            assertContains(res, "public object string")
            assertContains(res, "public object drawable")

            val strings = pkgDir.resolve("StringResources.kt").readText()
            assertContains(strings, "public val Res.string.projects: StringResource")
            assertContains(strings, "LanguageQualifier(\"pt\")")
            assertContains(strings, "RegionQualifier(\"BR\")")

            assertContains(pkgDir.resolve("FontResources.kt").readText(), "Res.font.mono_regular: FontResource")
            assertContains(pkgDir.resolve("DrawableResources.kt").readText(), "Res.drawable.logo: DrawableResource")

            val staged = resourcesDir.resolve("composeResources/com.example.generated.resources")
            assertTrue(Files.isRegularFile(staged.resolve("drawable/logo.png")), "drawable copied through")
            assertTrue(Files.isRegularFile(staged.resolve("font/mono_regular.ttf")), "font copied through")
            assertTrue(
                Files.notExists(staged.resolve("values/strings.xml")),
                "values XML is converted, never staged as XML",
            )
        }
    }

    /**
     * The accessors index into the `.cvr` by byte offset and length. This reads one back the way the runtime
     * does — take the range, split on `|`, decode the payload — which is the only check that proves the two
     * halves of the generator agree.
     */
    @Test
    fun theByteRangesInTheAccessorsAddressTheRightRecords() {
        withTempDir("compose-res-offsets") { dir ->
            val (kotlinDir, resourcesDir, _) = generate(dir)
            val strings = kotlinDir.resolve("com/example/generated/resources/StringResources.kt").readText()
            val cvr = resourcesDir
                .resolve("composeResources/com.example.generated.resources/values/strings.commonMain.cvr")
                .let { Files.readAllBytes(it) }

            assertEquals("version:0", String(cvr, 0, 9), "the file announces its format first")

            fun valueAt(offset: Int, size: Int): Pair<String, String> {
                val record = String(cvr, offset, size, Charsets.UTF_8).split('|')
                return record[1] to String(Base64.getDecoder().decode(record[2]), Charsets.UTF_8)
            }

            // `ResourceItem(setOf(), "${MD}values/strings.commonMain.cvr", 42, 31),`
            val item = Regex("""ResourceItem\(setOf\(\), "\$\{MD}values/strings\.commonMain\.cvr", (\d+), (\d+)\)""")
                .findAll(strings)
                .map { valueAt(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
                .toMap()

            assertEquals("Projects", item["projects"])
            assertEquals("Hello & welcome", item["greeting"], "XML entities are resolved")
        }
    }

    @Test
    fun packsPluralsAndArraysAsOneRecordEach() {
        withTempDir("compose-res-plurals") { dir ->
            val (_, resourcesDir, _) = generate(dir)
            val cvr = resourcesDir
                .resolve("composeResources/com.example.generated.resources/values/strings.commonMain.cvr")
                .readText()

            val plural = cvr.lineSequence().single { it.startsWith("plurals|item_count|") }
            val quantities = plural.substringAfter("plurals|item_count|").split(',')
                .associate { it.substringBefore(':') to String(Base64.getDecoder().decode(it.substringAfter(':'))) }
            assertEquals(mapOf("ONE" to "%1\$d item", "OTHER" to "%1\$d items"), quantities)

            val array = cvr.lineSequence().single { it.startsWith("string-array|weekdays|") }
            val values = array.substringAfter("string-array|weekdays|").split(',')
                .map { String(Base64.getDecoder().decode(it)) }
            assertEquals(listOf("Mon", "Tue"), values)
        }
    }

    /** `publicResClass = false` is the default for a module whose resources are its own business. */
    @Test
    fun keepsTheResClassInternalWhenTheModuleAsksFor() {
        withTempDir("compose-res-internal") { dir ->
            val (kotlinDir, _, _) = generate(dir, public = false)
            val pkgDir = kotlinDir.resolve("com/example/generated/resources")
            assertContains(pkgDir.resolve("Res.kt").readText(), "internal object Res")
            assertContains(
                pkgDir.resolve("StringResources.kt").readText(),
                "internal val Res.string.projects",
            )
        }
    }

    /** A type with no files still needs its accessor object and its `all…` map, or code written for one
     *  that will exist stops compiling. */
    @Test
    fun declaresEveryResourceTypeEvenWithNoResourcesOfIt() {
        withTempDir("compose-res-empty") { dir ->
            val root = dir.resolve("src/commonMain/composeResources/drawable")
            Files.createDirectories(root)
            root.resolve("only.png").writeText("x")
            val kotlinDir = dir.resolve("out/kotlin")
            ComposeResourcesGenerator("com.example.res", publicResClass = true)
                .generate(listOf(root.parent), kotlinDir, dir.resolve("out/resources"))

            val res = kotlinDir.resolve("com/example/res/Res.kt").readText()
            assertContains(res, "public object plurals")
            assertContains(res, "public val Res.allStringResources: Map<String, StringResource>")
            assertContains(res, "get() = emptyMap()")
        }
    }
}
