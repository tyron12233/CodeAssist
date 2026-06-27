package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.interp.KotlinComposePreviews
import dev.ide.lang.kotlin.interp.PreviewConstants
import dev.ide.lang.kotlin.parse.KotlinParserHost
import org.jetbrains.kotlin.psi.KtFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parsing `@Preview` annotation arguments and expanding variants (stacked previews, MultiPreview annotations,
 * `@PreviewParameter`) into [KotlinComposePreviews]'s per-variant entries. Pure PSI, no symbol service.
 */
class KotlinPreviewArgsTest {

    private fun parse(code: String): KtFile = KotlinParserHost.parse("Ui.kt", code.trimIndent())

    private val preamble = """
        package demo
        annotation class Composable
        annotation class Preview(
            val name: String = "", val group: String = "", val apiLevel: Int = -1,
            val widthDp: Int = -1, val heightDp: Int = -1, val locale: String = "",
            val fontScale: Float = 1f, val showSystemUi: Boolean = false, val showBackground: Boolean = false,
            val backgroundColor: Long = 0, val uiMode: Int = 0, val device: String = "",
        )
    """.trimIndent()

    @Test
    fun parsesNamedScalarArguments() {
        val previews = KotlinComposePreviews.find(
            parse(
                """
                $preamble
                @Preview(name = "Big", group = "screens", widthDp = 320, heightDp = 640,
                         fontScale = 1.5f, showBackground = true, backgroundColor = 0xFF112233,
                         locale = "fr", apiLevel = 33, showSystemUi = true)
                @Composable fun P() {}
                """,
            ),
        )
        val cfg = assertNotNull(previews.singleOrNull(), "one variant").config
        assertEquals("Big", cfg.name)
        assertEquals("screens", cfg.group)
        assertEquals(320, cfg.widthDp)
        assertEquals(640, cfg.heightDp)
        assertEquals(1.5f, cfg.fontScale)
        assertTrue(cfg.showBackground)
        assertEquals(0xFF112233L, cfg.backgroundColor)
        assertEquals("fr", cfg.locale)
        assertEquals(33, cfg.apiLevel)
        assertTrue(cfg.showSystemUi)
    }

    @Test
    fun foldsUiModeNamedConstantAndDetectsNight() {
        val previews = KotlinComposePreviews.find(
            parse(
                """
                $preamble
                @Preview(uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
                @Composable fun P() {}
                """,
            ),
        )
        val cfg = previews.single().config
        assertEquals(PreviewConstants.UI_MODE_NIGHT_YES, cfg.uiMode)
        assertTrue(cfg.isNight, "UI_MODE_NIGHT_YES should read as night")
    }

    @Test
    fun foldsUiModeOrCombination() {
        val previews = KotlinComposePreviews.find(
            parse(
                """
                $preamble
                @Preview(uiMode = 0x20 or 0x01)
                @Composable fun P() {}
                """,
            ),
        )
        assertEquals(0x21, previews.single().config.uiMode)
        assertTrue(previews.single().config.isNight)
    }

    @Test
    fun resolvesDeviceConstantAndLiteral() {
        val byConst = KotlinComposePreviews.find(
            parse("$preamble\n@Preview(device = androidx.compose.ui.tooling.preview.Devices.PIXEL_4)\n@Composable fun A() {}"),
        )
        assertEquals("id:pixel_4", byConst.single().config.device)
        val byLiteral = KotlinComposePreviews.find(
            parse("$preamble\n@Preview(device = \"spec:width=411dp,height=891dp\")\n@Composable fun B() {}"),
        )
        assertEquals("spec:width=411dp,height=891dp", byLiteral.single().config.device)
    }

    @Test
    fun supportsPositionalArguments() {
        // @Preview(name, group, apiLevel, widthDp, ...) positionally.
        val previews = KotlinComposePreviews.find(
            parse("$preamble\n@Preview(\"Hero\", \"main\", 31, 200)\n@Composable fun P() {}"),
        )
        val cfg = previews.single().config
        assertEquals("Hero", cfg.name)
        assertEquals("main", cfg.group)
        assertEquals(31, cfg.apiLevel)
        assertEquals(200, cfg.widthDp)
    }

    @Test
    fun stackedPreviewsBecomeDistinctVariants() {
        val previews = KotlinComposePreviews.find(
            parse(
                """
                $preamble
                @Preview(name = "Light")
                @Preview(name = "Dark", uiMode = 0x20)
                @Composable fun P() {}
                """,
            ),
        )
        assertEquals(listOf("Light", "Dark"), previews.map { it.label })
        assertEquals(2, previews.map { it.variantId }.toSet().size, "variant ids must be unique")
        assertTrue(previews.all { it.functionName == "P" })
        assertTrue(previews[1].config.isNight)
    }

    @Test
    fun expandsBuiltinMultiPreview() {
        val previews = KotlinComposePreviews.find(
            parse("$preamble\n@androidx.compose.ui.tooling.preview.PreviewLightDark\n@Composable fun P() {}"),
        )
        assertEquals(listOf("Light", "Dark"), previews.map { it.config.name })
        assertTrue(previews.last().config.isNight)
    }

    @Test
    fun expandsSameFileMultiPreviewAnnotationClass() {
        val previews = KotlinComposePreviews.find(
            parse(
                """
                $preamble
                @Preview(name = "Phone", widthDp = 360)
                @Preview(name = "Tablet", widthDp = 720)
                annotation class Devices2

                @Devices2
                @Composable fun P() {}
                """,
            ),
        )
        assertEquals(listOf("Phone", "Tablet"), previews.map { it.config.name })
        assertEquals(720, previews.last().config.widthDp)
    }

    @Test
    fun detectsPreviewParameterProvider() {
        val previews = KotlinComposePreviews.find(
            parse(
                """
                $preamble
                class NameProvider
                @Preview @Composable fun P(@PreviewParameter(NameProvider::class, limit = 3) n: String) {}
                """,
            ),
        )
        val p = previews.single()
        assertEquals(1, p.arity)
        val param = assertNotNull(p.parameter, "provider detected")
        assertEquals("NameProvider", param.providerName)
        assertEquals(3, param.limit)
    }

    @Test
    fun composableWithoutPreviewIsNotATarget() {
        val previews = KotlinComposePreviews.find(parse("$preamble\n@Composable fun Plain() {}\nfun bare() {}"))
        assertTrue(previews.isEmpty())
    }

    @Test
    fun singleVariantKeepsPlainName() {
        val previews = KotlinComposePreviews.find(parse("$preamble\n@Preview @Composable fun Hello() {}"))
        val p = previews.single()
        assertEquals("Hello", p.variantId)
        assertEquals("Hello", p.label)
        assertNull(p.parameter)
        assertEquals(0, p.arity)
    }
}
