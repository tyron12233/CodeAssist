package dev.ide.ui.theme.colors

import androidx.compose.ui.graphics.Color
import dev.ide.ui.theme.colors.ColorKeys as K

/**
 * The shipped, read-only schemes.
 *
 * [DEFAULT] is deliberately EMPTY on both sides: the attribute defaults in [BuiltInColorAttributes] ARE the
 * CodeAssist palette, and its chrome is left to the live Material theme, so the default scheme is the
 * absence of a scheme and a fresh install renders exactly as the build before schemes existed.
 *
 * The rest are ports of palettes people already know. Each pins its own editor background, selection and
 * gutter, because half of what makes Solarized recognisably Solarized is the paper it is printed on.
 */
object BuiltInColorSchemes {

    const val DEFAULT_ID = "codeassist"

    val DEFAULT = EditorColorScheme(
        id = DEFAULT_ID,
        name = "CodeAssist",
        builtIn = true,
    )

    val DARCULA = EditorColorScheme(
        id = "darcula", name = "Darcula", builtIn = true,
        dark = variant(
            background = 0xFF2B2B2B, caret = 0xFFBBBBBB, selection = 0xFF214283, currentLine = 0xFF323232,
            gutter = 0xFF606366, gutterCurrent = 0xFFA4A3A3, gutterBorder = 0xFF3C3F41,
            text = 0xFFA9B7C6, keyword = 0xFFCC7832, keywordBold = true, string = 0xFF6A8759,
            number = 0xFF6897BB, comment = 0xFF808080, annotation = 0xFFBBB529, function = 0xFFFFC66D,
            type = 0xFFA9B7C6, property = 0xFF9876AA, variable = 0xFFA9B7C6, constant = 0xFF9876AA,
            punctuation = 0xFFA9B7C6, label = 0xFFBBB529,
            composable = 0xFF6A8759, extension = 0xFFFFC66D, suspendFn = 0xFF9876AA, mutableVar = 0xFFA9B7C6,
        ),
        light = variant(
            background = 0xFFFFFFFF, caret = 0xFF000000, selection = 0xFFA6D2FF, currentLine = 0xFFFCFAED,
            gutter = 0xFF999999, gutterCurrent = 0xFF3C3C3C, gutterBorder = 0xFFE8E8E8,
            text = 0xFF080808, keyword = 0xFF0033B3, keywordBold = true, string = 0xFF067D17,
            number = 0xFF1750EB, comment = 0xFF8C8C8C, annotation = 0xFF9E880D, function = 0xFF00627A,
            type = 0xFF080808, property = 0xFF871094, variable = 0xFF080808, constant = 0xFF871094,
            punctuation = 0xFF080808, label = 0xFF9E880D,
            composable = 0xFF067D17, extension = 0xFF00627A, suspendFn = 0xFF871094, mutableVar = 0xFF080808,
        ),
    )

    val ONE = EditorColorScheme(
        id = "one", name = "One", builtIn = true,
        dark = variant(
            background = 0xFF282C34, caret = 0xFF528BFF, selection = 0xFF3E4451, currentLine = 0xFF2C313C,
            gutter = 0xFF4B5263, gutterCurrent = 0xFF9DA5B4, gutterBorder = 0xFF31363F,
            text = 0xFFABB2BF, keyword = 0xFFC678DD, string = 0xFF98C379, number = 0xFFD19A66,
            comment = 0xFF5C6370, annotation = 0xFFE5C07B, function = 0xFF61AFEF, type = 0xFFE5C07B,
            property = 0xFF56B6C2, variable = 0xFFE06C75, constant = 0xFFD19A66, punctuation = 0xFFABB2BF,
            label = 0xFFE5C07B,
            composable = 0xFF56B6C2, extension = 0xFF61AFEF, suspendFn = 0xFFC678DD, mutableVar = 0xFFE06C75,
        ),
        light = variant(
            background = 0xFFFAFAFA, caret = 0xFF526FFF, selection = 0xFFD7DAE0, currentLine = 0xFFF0F0F0,
            gutter = 0xFF9D9D9F, gutterCurrent = 0xFF383A42, gutterBorder = 0xFFE5E5E6,
            text = 0xFF383A42, keyword = 0xFFA626A4, string = 0xFF50A14F, number = 0xFF986801,
            comment = 0xFFA0A1A7, annotation = 0xFFC18401, function = 0xFF4078F2, type = 0xFFC18401,
            property = 0xFF0184BC, variable = 0xFFE45649, constant = 0xFF986801, punctuation = 0xFF383A42,
            label = 0xFFC18401,
            composable = 0xFF0184BC, extension = 0xFF4078F2, suspendFn = 0xFFA626A4, mutableVar = 0xFFE45649,
        ),
    )

    val SOLARIZED = EditorColorScheme(
        id = "solarized", name = "Solarized", builtIn = true,
        dark = variant(
            background = 0xFF002B36, caret = 0xFF93A1A1, selection = 0xFF073642, currentLine = 0xFF073642,
            gutter = 0xFF586E75, gutterCurrent = 0xFF93A1A1, gutterBorder = 0xFF073642,
            text = 0xFF839496, keyword = 0xFF859900, string = 0xFF2AA198, number = 0xFFD33682,
            comment = 0xFF586E75, annotation = 0xFFB58900, function = 0xFF268BD2, type = 0xFFB58900,
            property = 0xFF2AA198, variable = 0xFFCB4B16, constant = 0xFFD33682, punctuation = 0xFF657B83,
            label = 0xFF6C71C4,
            composable = 0xFF859900, extension = 0xFF268BD2, suspendFn = 0xFF6C71C4, mutableVar = 0xFFCB4B16,
        ),
        light = variant(
            background = 0xFFFDF6E3, caret = 0xFF586E75, selection = 0xFFEEE8D5, currentLine = 0xFFEEE8D5,
            gutter = 0xFF93A1A1, gutterCurrent = 0xFF586E75, gutterBorder = 0xFFEEE8D5,
            text = 0xFF657B83, keyword = 0xFF859900, string = 0xFF2AA198, number = 0xFFD33682,
            comment = 0xFF93A1A1, annotation = 0xFFB58900, function = 0xFF268BD2, type = 0xFFB58900,
            property = 0xFF2AA198, variable = 0xFFCB4B16, constant = 0xFFD33682, punctuation = 0xFF93A1A1,
            label = 0xFF6C71C4,
            composable = 0xFF859900, extension = 0xFF268BD2, suspendFn = 0xFF6C71C4, mutableVar = 0xFFCB4B16,
        ),
    )

    val GITHUB = EditorColorScheme(
        id = "github", name = "GitHub", builtIn = true,
        dark = variant(
            background = 0xFF0D1117, caret = 0xFF58A6FF, selection = 0xFF264F78, currentLine = 0xFF161B22,
            gutter = 0xFF6E7681, gutterCurrent = 0xFFC9D1D9, gutterBorder = 0xFF21262D,
            text = 0xFFE6EDF3, keyword = 0xFFFF7B72, string = 0xFFA5D6FF, number = 0xFF79C0FF,
            comment = 0xFF8B949E, annotation = 0xFFD2A8FF, function = 0xFFD2A8FF, type = 0xFFFFA657,
            property = 0xFF79C0FF, variable = 0xFFFFA657, constant = 0xFF79C0FF, punctuation = 0xFFC9D1D9,
            label = 0xFFFFA657,
            composable = 0xFF7EE787, extension = 0xFFD2A8FF, suspendFn = 0xFFFF7B72, mutableVar = 0xFFFFA657,
        ),
        light = variant(
            background = 0xFFFFFFFF, caret = 0xFF0969DA, selection = 0xFFB6D6FD, currentLine = 0xFFF6F8FA,
            gutter = 0xFF8C959F, gutterCurrent = 0xFF1F2328, gutterBorder = 0xFFD1D9E0,
            text = 0xFF1F2328, keyword = 0xFFCF222E, string = 0xFF0A3069, number = 0xFF0550AE,
            comment = 0xFF6E7781, annotation = 0xFF8250DF, function = 0xFF8250DF, type = 0xFF953800,
            property = 0xFF0550AE, variable = 0xFF953800, constant = 0xFF0550AE, punctuation = 0xFF1F2328,
            label = 0xFF953800,
            composable = 0xFF1A7F37, extension = 0xFF8250DF, suspendFn = 0xFFCF222E, mutableVar = 0xFF953800,
        ),
    )

    val all: List<EditorColorScheme> = listOf(DEFAULT, DARCULA, ONE, SOLARIZED, GITHUB)

    fun byId(id: String): EditorColorScheme? = all.firstOrNull { it.id == id }

    /**
     * One preset variant, written as the handful of anchors a palette actually names.
     *
     * Everything a preset leaves out — modifier keywords, escapes, type parameters, the XML and Markdown
     * groups — reaches its color through the fallback chain, which is the whole reason the chain exists: a
     * palette designed years before this editor distinguished a suspend function still colors one.
     */
    private fun variant(
        background: Long, caret: Long, selection: Long, currentLine: Long,
        gutter: Long, gutterCurrent: Long, gutterBorder: Long,
        text: Long, keyword: Long, string: Long, number: Long, comment: Long, annotation: Long,
        function: Long, type: Long, property: Long, variable: Long, constant: Long, punctuation: Long,
        label: Long, composable: Long, extension: Long, suspendFn: Long, mutableVar: Long,
        keywordBold: Boolean = false,
    ): Map<String, AttributeStyle> = buildMap {
        put(K.EDITOR_BACKGROUND, AttributeStyle.bg(Color(background)))
        put(K.EDITOR_CARET, AttributeStyle.fg(Color(caret)))
        put(K.EDITOR_SELECTION, AttributeStyle.bg(Color(selection)))
        put(K.EDITOR_CURRENT_LINE, AttributeStyle.bg(Color(currentLine)))
        put(K.GUTTER_TEXT, AttributeStyle.fg(Color(gutter)))
        put(K.GUTTER_CURRENT, AttributeStyle.fg(Color(gutterCurrent)))
        put(K.GUTTER_BORDER, AttributeStyle.fg(Color(gutterBorder)))
        put(K.TEXT, AttributeStyle.fg(Color(text)))
        put(K.KEYWORD, AttributeStyle(foreground = Color(keyword), bold = if (keywordBold) true else null))
        put(K.STRING, AttributeStyle.fg(Color(string)))
        put(K.NUMBER, AttributeStyle.fg(Color(number)))
        put(K.COMMENT, AttributeStyle(foreground = Color(comment), italic = true))
        put(K.ANNOTATION, AttributeStyle.fg(Color(annotation)))
        put(K.FUNCTION, AttributeStyle.fg(Color(function)))
        put(K.TYPE, AttributeStyle.fg(Color(type)))
        put(K.PROPERTY, AttributeStyle.fg(Color(property)))
        put(K.VARIABLE, AttributeStyle.fg(Color(variable)))
        put(K.CONSTANT, AttributeStyle.fg(Color(constant)))
        put(K.PUNCTUATION, AttributeStyle.fg(Color(punctuation)))
        put(K.LABEL, AttributeStyle(foreground = Color(label), italic = true))
        put(K.KOTLIN_COMPOSABLE, AttributeStyle(foreground = Color(composable), bold = true))
        put(K.KOTLIN_EXTENSION, AttributeStyle(foreground = Color(extension), italic = true))
        put(K.KOTLIN_SUSPEND, AttributeStyle(foreground = Color(suspendFn), italic = true))
        put(K.KOTLIN_MUTABLE, AttributeStyle(foreground = Color(mutableVar), underline = true))
        // The interpolation delimiters have to leave the string to read as code; every palette here agrees
        // with the shipped one that the keyword color is what does that.
        put(K.STRING_TEMPLATE, AttributeStyle.fg(Color(keyword)))
        put(K.STRING_ESCAPE, AttributeStyle.fg(Color(number)))
    }
}
