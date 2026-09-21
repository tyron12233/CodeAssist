package dev.ide.ui.theme.colors

import androidx.compose.ui.graphics.Color
import dev.ide.ui.editor.CodeLanguage
import dev.ide.ui.editor.core.TokenType
import dev.ide.ui.editor.core.tokenColorKey
import dev.ide.ui.theme.toSyntaxColors
import dev.ide.ui.backend.UiHighlightModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The scheme model's contract: resolution through defaults and the fallback chain, the shipped palette
 * being reproduced exactly by an empty scheme, and a scheme surviving a round trip through its file format.
 *
 * These are the parts a rendering bug would come from and the parts no screenshot would catch — "the
 * default scheme renders like the old build" is a claim about thirty-odd colors that is only checkable by
 * asserting it.
 */
class EditorColorSchemeTest {

    private fun resolve(
        scheme: EditorColorScheme = BuiltInColorSchemes.DEFAULT,
        isDark: Boolean = true,
        defaults: SchemeDefaults = SchemeDefaults.EMPTY,
    ) = ResolvedColorScheme(scheme, isDark, defaults)

    @Test
    fun defaultSchemeReproducesTheShippedPalette() {
        val dark = resolve().toSyntaxColors()
        // The values the editor was hardcoded to before schemes existed. If one of these moves, every
        // existing install's editor changes color on update, which is exactly the thing to be told about.
        assertEquals(Color(0xFFC7C8CF), dark.default)
        assertEquals(Color(0xFFCD7EE0), dark.keyword)
        assertEquals(Color(0xFF98C97A), dark.string)
        assertEquals(Color(0xFF61AFEF), dark.func)
        assertEquals(Color(0xFF4FC1A6), dark.composable)
        assertEquals(Color(0xFF82AAFF), dark.extension)

        val light = resolve(isDark = false).toSyntaxColors()
        assertEquals(Color(0xFF34363D), light.default)
        assertEquals(Color(0xFFA32FB0), light.keyword)
        assertEquals(Color(0xFF3F9C45), light.string)
        assertEquals(Color(0xFF1F8A77), light.composable)
    }

    @Test
    fun anAttributeWithNoColorOfItsOwnTakesItsParentsColor() {
        val colors = resolve()
        // `keyword.modifier` and `type.parameter` ship with no color: they exist so a scheme CAN separate
        // them, and until one does they have to be indistinguishable from their parent rather than blank.
        assertEquals(colors.colorOf(ColorKeys.KEYWORD), colors.colorOf(ColorKeys.KEYWORD_MODIFIER))
        assertEquals(colors.colorOf(ColorKeys.TYPE), colors.colorOf(ColorKeys.TYPE_PARAMETER))
        assertEquals(colors.colorOf(ColorKeys.VARIABLE), colors.colorOf(ColorKeys.VARIABLE_PARAMETER))
        assertEquals(colors.colorOf(ColorKeys.TYPE), colors.colorOf(ColorKeys.XML_TAG))
        assertEquals(colors.colorOf(ColorKeys.FUNCTION), colors.colorOf(ColorKeys.MD_LINK))
    }

    @Test
    fun overridingAParentMovesEveryChildThatHasNoColorOfItsOwn() {
        val scheme = EditorColorScheme("t", "T").withStyle(true, ColorKeys.TYPE, AttributeStyle.fg(Color.Red))
        val colors = resolve(scheme)
        assertEquals(Color.Red, colors.colorOf(ColorKeys.TYPE))
        assertEquals(Color.Red, colors.colorOf(ColorKeys.TYPE_PARAMETER))
        assertEquals(Color.Red, colors.colorOf(ColorKeys.XML_TAG))
        assertEquals(Color.Red, colors.colorOf(ColorKeys.MD_HEADING))
        // …and nothing else.
        assertEquals(resolve().colorOf(ColorKeys.FUNCTION), colors.colorOf(ColorKeys.FUNCTION))
    }

    @Test
    fun anAttributeWithItsOwnDefaultIgnoresTheParentUntilToldToInherit() {
        val recolored = EditorColorScheme("t", "T")
            .withStyle(true, ColorKeys.FUNCTION, AttributeStyle.fg(Color.Red))
        // An extension function has its own color by design, so moving `function` must not move it.
        assertEquals(Color(0xFF82AAFF), resolve(recolored).colorOf(ColorKeys.KOTLIN_EXTENSION))

        // `inherit` is how the user says "actually, just look like a function" — and keeps the italic.
        val inherited = recolored.withStyle(
            true, ColorKeys.KOTLIN_EXTENSION, AttributeStyle(inheritParent = true, italic = true),
        )
        val colors = resolve(inherited)
        assertEquals(Color.Red, colors.colorOf(ColorKeys.KOTLIN_EXTENSION))
        assertEquals(true, colors.styleOf(ColorKeys.KOTLIN_EXTENSION).italic)
    }

    @Test
    fun everyTextAttributeResolvesToSomeColorInEveryShippedScheme() {
        for (scheme in BuiltInColorSchemes.all) {
            for (isDark in listOf(true, false)) {
                val colors = resolve(scheme, isDark)
                for (attribute in ColorAttributes.all()) {
                    if (attribute.overlay || attribute.parent == null) continue
                    assertNotNull(
                        colors.styleOf(attribute.key).foreground,
                        "${scheme.name} (dark=$isDark) leaves ${attribute.key} with no color",
                    )
                }
            }
        }
    }

    @Test
    fun chromeStaysUnsetSoTheLiveThemeShowsThrough() {
        // The caret and the selection follow the Material accent until a scheme pins them; if resolution
        // ever filled them in from the root text color, Material You would stop reaching the editor.
        val bare = resolve()
        assertNull(bare.styleOf(ColorKeys.EDITOR_CARET).foreground)
        assertNull(bare.styleOf(ColorKeys.EDITOR_SELECTION).background)
        assertEquals(Color.Magenta, bare.caret(Color.Magenta))

        val themed = resolve(defaults = SchemeDefaults(mapOf(ColorKeys.EDITOR_CARET to AttributeStyle.fg(Color.Green))))
        assertEquals(Color.Green, themed.caret(Color.Magenta))

        // A preset that DOES pin them wins over both.
        val solarized = resolve(BuiltInColorSchemes.SOLARIZED)
        assertEquals(Color(0xFF93A1A1), solarized.caret(Color.Magenta))
        assertEquals(Color(0xFF002B36), solarized.background(Color.Magenta))
    }

    @Test
    fun overlayAttributesAddAStyleWithoutReplacingTheBaseColor() {
        val colors = resolve()
        val plain = colors.semanticStyle("method", emptySet())
        val deprecated = colors.semanticStyle("method", setOf(UiHighlightModifier.Deprecated))
        assertNotNull(plain)
        assertNotNull(deprecated)
        assertEquals(plain.foreground, deprecated.foreground)
        assertEquals(true, deprecated.strikethrough)
        assertNull(plain.strikethrough)
    }

    @Test
    fun modifiersLayerInPrecedenceOrder() {
        val colors = resolve()
        // A suspend extension reads as suspend: the later modifier wins the color, as it always has.
        val suspendExtension = colors.semanticStyle(
            "function", setOf(UiHighlightModifier.Extension, UiHighlightModifier.Suspend),
        )
        assertEquals(colors.colorOf(ColorKeys.KOTLIN_SUSPEND), suspendExtension?.foreground)
        // …and still carries the italic both of them ask for.
        assertEquals(true, suspendExtension?.italic)
    }

    @Test
    fun anUnknownSemanticKindIsLeftToTheLexer() {
        assertNull(semanticColorKey("somethingNobodyRegistered"))
        assertNull(resolve().semanticStyle("somethingNobodyRegistered", emptySet()))
    }

    @Test
    fun aRegisteredKeyIsAlsoUsableAsASemanticKind() {
        // The other half of a contributed language's colors. The lexical mapping can only redirect token
        // types the shared scanner distinguishes, so a construct it lumps in with keywords (a C
        // preprocessor directive) is only separable by the analyzer — which names it with a kind of its
        // own, and that kind has to reach an attribute.
        val key = "mylang.directive"
        val registration = ColorAttributes.register(
            ColorAttribute(key, "Directive", ColorGroups.CODE, ColorKeys.KEYWORD,
                defaultDark = AttributeStyle(bold = true), defaultLight = AttributeStyle(bold = true)),
        )
        try {
            assertEquals(key, semanticColorKey(key))
            val style = resolve().semanticStyle(key, emptySet())
            assertNotNull(style)
            assertEquals(resolve().colorOf(ColorKeys.KEYWORD), style.foreground)
            assertEquals(true, style.bold)
        } finally {
            registration.dispose()
        }
        assertNull(semanticColorKey(key))
    }

    @Test
    fun theSameTokenTypeMeansDifferentThingsInDifferentLanguages() {
        assertEquals(ColorKeys.TYPE, tokenColorKey(CodeLanguage.Kotlin.profile, TokenType.TYPE))
        assertEquals(ColorKeys.XML_TAG, tokenColorKey(CodeLanguage.Xml.profile, TokenType.TYPE))
        assertEquals(ColorKeys.XML_ATTRIBUTE, tokenColorKey(CodeLanguage.Xml.profile, TokenType.PROPERTY))
        assertEquals(ColorKeys.MD_HEADING, tokenColorKey(CodeLanguage.Markdown.profile, TokenType.TYPE))
        assertEquals(ColorKeys.MD_LIST_MARKER, tokenColorKey(CodeLanguage.Markdown.profile, TokenType.KEYWORD))
    }

    @Test
    fun everyLexicalTokenTypeHasAnAttributeInEveryFamily() {
        for (language in listOf(CodeLanguage.Java, CodeLanguage.Kotlin, CodeLanguage.Xml, CodeLanguage.Markdown)) {
            for (type in TokenType.entries) {
                val key = tokenColorKey(language.profile, type)
                assertNotNull(ColorAttributes.byKey(key), "$language/$type maps to unregistered '$key'")
            }
        }
    }

    @Test
    fun aRegisteredAttributeIsEditableAndDisposable() {
        val key = "mylang.directive"
        val registration = ColorAttributes.register(
            ColorAttribute(key, "Directive", ColorGroups.CODE, ColorKeys.KEYWORD),
        )
        try {
            val colors = resolve()
            // A contributed attribute nobody has styled still colors, through the chain it declared.
            assertEquals(colors.colorOf(ColorKeys.KEYWORD), colors.colorOf(key))
            assertTrue(ColorAttributes.all().any { it.key == key })
        } finally {
            registration.dispose()
        }
        assertNull(ColorAttributes.byKey(key))
    }

    @Test
    fun overridingABuiltInAttributePutsItBackOnDispose() {
        val original = ColorAttributes.byKey(ColorKeys.KEYWORD)
        assertNotNull(original)
        val registration = ColorAttributes.register(
            ColorAttribute(ColorKeys.KEYWORD, "Keyword", ColorGroups.CODE, ColorKeys.TEXT,
                defaultDark = AttributeStyle.fg(Color.Red), defaultLight = AttributeStyle.fg(Color.Red)),
        )
        assertEquals(Color.Red, resolve().colorOf(ColorKeys.KEYWORD))
        registration.dispose()
        // Removing rather than restoring would take the built-in out with the plugin that shadowed it, and
        // keywords would go uncolored and uneditable until the next launch.
        assertSame(original, ColorAttributes.byKey(ColorKeys.KEYWORD))
        assertEquals(Color(0xFFCD7EE0), resolve().colorOf(ColorKeys.KEYWORD))
    }

    @Test
    fun disposingAnOverrideDoesNotClobberOneThatLandedOnTopOfIt() {
        val first = ColorAttributes.register(
            ColorAttribute(ColorKeys.KEYWORD, "Keyword", ColorGroups.CODE, defaultDark = AttributeStyle.fg(Color.Red)),
        )
        val second = ColorAttributes.register(
            ColorAttribute(ColorKeys.KEYWORD, "Keyword", ColorGroups.CODE, defaultDark = AttributeStyle.fg(Color.Blue)),
        )
        try {
            // Unloading the first plugin must not reach past the second one and put the built-in back.
            first.dispose()
            assertEquals(Color.Blue, resolve().colorOf(ColorKeys.KEYWORD))
        } finally {
            second.dispose()
        }
        assertEquals(Color(0xFFCD7EE0), resolve().colorOf(ColorKeys.KEYWORD))
    }

    @Test
    fun anAttributeInAGroupNobodyDeclaredStillGetsASection() {
        val registration = ColorAttributes.register(
            ColorAttribute("glsl.qualifier", "Storage qualifier", "GLSL", ColorKeys.KEYWORD),
        )
        try {
            val grouped = ColorAttributes.grouped()
            val section = grouped.firstOrNull { (group, _) -> group.id == "GLSL" }
            assertNotNull(section, "a group named only by an attribute must still render")
            assertEquals(listOf("glsl.qualifier"), section.second.map { it.key })
            // After the shell's own sections, so a contribution cannot push the built-ins down the screen.
            assertEquals("GLSL", grouped.last().first.id)
        } finally {
            registration.dispose()
        }
        assertTrue(ColorAttributes.grouped().none { (group, _) -> group.id == "GLSL" })
    }

    @Test
    fun aStyleWithNoOpinionIsDroppedRatherThanStored() {
        val scheme = EditorColorScheme("t", "T")
            .withStyle(true, ColorKeys.KEYWORD, AttributeStyle.fg(Color.Red))
            .withStyle(true, ColorKeys.KEYWORD, AttributeStyle.EMPTY)
        assertTrue(scheme.dark.isEmpty(), "clearing an override must remove it, not store an empty one")
    }

    @Test
    fun editingOneVariantLeavesTheOtherAlone() {
        val scheme = EditorColorScheme("t", "T").withStyle(true, ColorKeys.KEYWORD, AttributeStyle.fg(Color.Red))
        assertEquals(Color.Red, resolve(scheme, isDark = true).colorOf(ColorKeys.KEYWORD))
        assertEquals(Color(0xFFA32FB0), resolve(scheme, isDark = false).colorOf(ColorKeys.KEYWORD))
    }

    @Test
    fun spanStylesArePrecomputedRatherThanBuiltPerLookup() {
        val colors = resolve()
        assertSame(colors.spanStyleOf(ColorKeys.KEYWORD), colors.spanStyleOf(ColorKeys.KEYWORD))
    }
}
