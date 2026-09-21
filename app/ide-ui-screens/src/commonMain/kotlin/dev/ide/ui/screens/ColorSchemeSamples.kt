package dev.ide.ui.screens

import dev.ide.ui.theme.colors.ColorKeys as K

/**
 * The code the color scheme editor shows itself off with.
 *
 * The samples are pre-tagged rather than run through the real lexer and analyzer, for two reasons. The
 * analyzer half is not available here at all — a suspend extension function only colors as one once a
 * backend has resolved it, which needs an open project, an index and a parse — so a live-highlighted
 * sample would silently show half the attributes at their fallback colors and the user would be tuning
 * entries that appear to do nothing. And tagging is what makes the preview navigable: every run knows which
 * attribute drew it, so tapping a token in the sample selects that attribute instead of making the user
 * hunt for it in a list of sixty.
 *
 * Markup is `[[key|text]]`, with `+` joining the attributes layered on one run (`[[function+modifier.deprecated|old]]`)
 * exactly as the editor layers a semantic token's modifiers over its base. Anything that is not that exact
 * shape is literal text, so code full of brackets stays readable in source.
 */

/** One styled run of a sample line: [text] drawn with [keys] layered in order, or plain when empty. */
class SampleRun(val text: String, val keys: List<String>) {
    /** The attribute a tap on this run selects: the most specific one layered on it. */
    val primaryKey: String? get() = keys.lastOrNull()
}

/**
 * A sample plus the editor state it is shown in — a current line, a caret, a selection and a couple of
 * diagnostics — so the chrome attributes (which no amount of code can demonstrate on its own) have
 * something to color.
 */
class PreviewSample(
    val id: String,
    val title: String,
    val markup: String,
    val currentLine: Int,
    val caretColumn: Int,
    val selectionLine: Int,
    val selectionColumns: IntRange,
    val errorLine: Int,
    val errorColumns: IntRange,
    val warningLine: Int,
    val warningColumns: IntRange,
) {
    val lines: List<List<SampleRun>> by lazy { parseSample(markup) }

    /** Every attribute this sample actually demonstrates — used to jump the preview to the right tab. */
    val keys: Set<String> by lazy { lines.flatten().flatMap { it.keys }.toSet() }
}

/** Split `[[key|text]]` markup into per-line runs. Unterminated or malformed markup stays literal. */
fun parseSample(markup: String): List<List<SampleRun>> =
    markup.trimEnd('\n').split('\n').map { parseSampleLine(it) }

private fun parseSampleLine(line: String): List<SampleRun> {
    val runs = ArrayList<SampleRun>(8)
    var i = 0
    val literal = StringBuilder()
    fun flush() {
        if (literal.isNotEmpty()) {
            runs.add(SampleRun(literal.toString(), emptyList()))
            literal.clear()
        }
    }
    while (i < line.length) {
        val open = line.indexOf("[[", i)
        if (open < 0) {
            literal.append(line, i, line.length)
            break
        }
        val bar = line.indexOf('|', open + 2)
        val close = if (bar < 0) -1 else closingDelimiter(line, bar + 1)
        if (bar < 0 || close < 0) {
            literal.append(line, i, open + 2)
            i = open + 2
            continue
        }
        literal.append(line, i, open)
        flush()
        val keys = line.substring(open + 2, bar).split('+').map { it.trim() }.filter { it.isNotEmpty() }
        runs.add(SampleRun(line.substring(bar + 1, close), keys))
        i = close + 2
    }
    flush()
    return runs
}

/**
 * The first `]]` that closes a run rather than belonging to it.
 *
 * A Markdown link's text really is `[the guide]`, so its run ends `...guide]]]` and taking the first `]]`
 * would eat the bracket the sample exists to show. Skipping a `]]` immediately followed by another `]`
 * resolves that the way a reader does, without a quoting rule in the markup.
 */
private fun closingDelimiter(line: String, from: Int): Int {
    var at = line.indexOf("]]", from)
    while (at >= 0 && at + 2 < line.length && line[at + 2] == ']') at = line.indexOf("]]", at + 1)
    return at
}

/** The samples the preview offers, in tab order. */
object ColorSchemeSamples {

    val KOTLIN = PreviewSample(
        id = "kotlin",
        title = "Kotlin",
        currentLine = 7,
        caretColumn = 34,
        selectionLine = 9,
        selectionColumns = 8..25,
        errorLine = 16,
        errorColumns = 24..36,
        warningLine = 20,
        warningColumns = 4..9,
        markup = """
[[comment|// Every construct the editor can color, in one file.]]
[[keyword|package]] [[namespace|com.example.app]]

[[keyword|import]] [[namespace|androidx.compose.runtime.Composable]]

[[annotation|@Composable]]
[[keyword|fun]] [[function.declaration|Greeting]]([[variable.parameter|name]]: [[type|String]], [[variable.parameter|times]]: [[type|Int]] = [[number|1]]) {
    [[keyword|val]] [[variable|message]] = [[string|"Hello, ]][[string.template|${'$'}]][[variable|name]][[string|!]][[string.escape|\n]][[string|"]].[[function|trimEnd]]()
    [[keyword|var]] [[kotlin.variable.mutable|shown]] = [[number|0]]
    [[keyword|while]] ([[kotlin.variable.mutable|shown]] < [[variable.parameter|times]]) {
        [[kotlin.function.composable|Text]]([[variable|message]], [[variable.parameter|color]] = [[type|Theme]].[[property|accent]])
        [[kotlin.variable.mutable|shown]]++
    }
}

[[comment|/** Resolves the first match, or null once the budget runs out. */]]
[[keyword|suspend]] [[keyword|fun]] <[[type.parameter|T]]> [[type|List]]<[[type.parameter|T]]>.[[kotlin.function.extension|firstMatching]]([[variable.parameter|budget]]: [[type|Int]] = [[constant|MAX_TRIES]]): [[type.parameter|T]]? {
    [[label|scan@]] [[keyword|for]] ([[variable|item]] [[keyword|in]] [[keyword|this]]) {
        [[keyword|if]] ([[kotlin.function.suspend|matches]]([[variable|item]], [[variable.parameter|budget]])) [[keyword|return@]][[label|scan]] [[variable|item]]
    }
    [[keyword|return]] [[keyword|null]]
}

[[annotation|@Deprecated]]([[string|"Use Greeting instead"]])
[[keyword|fun]] [[function.declaration+modifier.deprecated|greet]]() = [[type|Strings]].[[property+modifier.static|APP_NAME]]
        """.trimIndent(),
    )

    val XML = PreviewSample(
        id = "xml",
        title = "XML",
        currentLine = 5,
        caretColumn = 36,
        selectionLine = 6,
        selectionColumns = 8..34,
        errorLine = 9,
        errorColumns = 8..26,
        warningLine = 3,
        warningColumns = 4..21,
        markup = """
[[xml.comment|<!-- A layout, coloring tags, attributes and references. -->]]
<[[xml.tag|LinearLayout]] [[xml.namespace|xmlns:android]]=[[xml.value|"http://schemas.android.com/apk/res/android"]]
    [[xml.namespace|android]]:[[xml.attribute|orientation]]=[[xml.value|"vertical"]]
    [[xml.namespace|android]]:[[xml.attribute|layout_width]]=[[xml.value|"match_parent"]]>

    <[[xml.tag|TextView]] [[xml.namespace|android]]:[[xml.attribute|id]]=[[xml.value|"]][[xml.reference|@+id/title]][[xml.value|"]]
        [[xml.namespace|android]]:[[xml.attribute|text]]=[[xml.value|"]][[xml.reference|@string/app_name]][[xml.value|"]]
        [[xml.namespace|android]]:[[xml.attribute|textColor]]=[[xml.value|"]][[xml.reference|?attr/colorPrimary]][[xml.value|"]]
        [[xml.namespace|android]]:[[xml.attribute|textSize]]=[[xml.value|"18sp"]]
        [[xml.namespace|android]]:[[xml.attribute|padding]]=[[xml.value|"@dimen/gap"]] />

</[[xml.tag|LinearLayout]]>
        """.trimIndent(),
    )

    val MARKDOWN = PreviewSample(
        id = "markdown",
        title = "Markdown",
        currentLine = 4,
        caretColumn = 12,
        selectionLine = 2,
        selectionColumns = 0..24,
        errorLine = 8,
        errorColumns = 2..14,
        warningLine = 6,
        warningColumns = 2..10,
        markup = """
[[markdown.heading|# Release notes]]

Ship the editor color scheme.

[[markdown.heading|## Highlights]]

[[markdown.listMarker|-]] Schemes carry a dark and a light variant.
[[markdown.listMarker|-]] Attributes fall back along a chain, so nothing is ever uncolored.
[[markdown.listMarker|-]] Run [[markdown.code|`./gradlew test`]] before tagging.

[[markdown.quote|> Imported schemes are renamed, never merged.]]

[[markdown.rule|---]]

See [[markdown.link|[the guide]]][[markdown.url|(docs/color-schemes.md)]] for the file format.
        """.trimIndent(),
    )

    val all = listOf(KOTLIN, XML, MARKDOWN)

    /**
     * The sample that best demonstrates [key] — the one the preview switches to when an attribute is
     * picked, so choosing "Tag name" does not leave the user staring at Kotlin.
     */
    fun bestFor(key: String): PreviewSample = all.firstOrNull { key in it.keys }
        ?: when {
            key.startsWith("xml.") -> XML
            key.startsWith("markdown.") -> MARKDOWN
            else -> KOTLIN
        }

    /** Attributes the samples cannot show as a token because they are the surface the tokens sit on. */
    val CHROME_KEYS = setOf(
        K.EDITOR_BACKGROUND, K.EDITOR_CARET, K.EDITOR_SELECTION, K.EDITOR_CURRENT_LINE,
        K.EDITOR_INDENT_GUIDE, K.EDITOR_FIND_MATCH, K.EDITOR_FIND_CURRENT, K.EDITOR_OCCURRENCE,
        K.EDITOR_TEMPLATE_FIELD, K.EDITOR_COMPOSING, K.EDITOR_INLAY_HINT, K.EDITOR_FOLD_PLACEHOLDER,
        K.GUTTER_TEXT, K.GUTTER_CURRENT, K.GUTTER_BORDER,
        K.DIAGNOSTIC_ERROR, K.DIAGNOSTIC_WARNING, K.DIAGNOSTIC_INFO,
    )
}
