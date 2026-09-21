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
 * The `]]` that closes a run: the LAST one before the next run starts (or before the end of the line).
 *
 * A run's own text can contain `]]` — a Markdown link's text really is `[the guide]`, and a CDATA section
 * really does end `]]>` — so taking the first `]]` eats exactly the bracket the sample exists to show.
 * Taking the last one inside the run's own region reads both correctly and needs no quoting rule, which
 * matters because the one character that would have to be quoted, a backslash, is itself something these
 * samples have to show (`\n` as a string escape).
 */
private fun closingDelimiter(line: String, from: Int): Int {
    val nextRun = line.indexOf("[[", from).let { if (it < 0) line.length else it }
    val at = line.lastIndexOf("]]", nextRun - 1)
    return if (at >= from) at else -1
}

/** The samples the preview offers, in tab order. */
object ColorSchemeSamples {

    val KOTLIN = PreviewSample(
        id = "kotlin",
        title = "Kotlin",
        currentLine = 8,
        caretColumn = 34,
        selectionLine = 10,
        selectionColumns = 8..25,
        errorLine = 43,
        errorColumns = 24..36,
        warningLine = 47,
        warningColumns = 4..9,
        markup = """
[[comment|// Every construct the editor can color, in one file.]]
[[keyword|package]] [[namespace|com.example.app]]

[[keyword|import]] [[namespace|androidx.compose.runtime.Composable]]

[[comment.doc|/** Greets whoever is passed in, as many times as asked. */]]
[[annotation|@Composable]]
[[keyword|fun]] [[function.topLevel+function.declaration|Greeting]][[punctuation.bracket|(]][[variable.parameter|name]][[punctuation.operator|:]] [[type.class|String]][[punctuation.separator|,]] [[variable.parameter|times]][[punctuation.operator|:]] [[type.class|Int]] [[punctuation.operator|=]] [[number|1]][[punctuation.bracket|)]] [[punctuation.bracket|{]]
    [[keyword|val]] [[variable|message]] [[punctuation.operator|=]] [[string|"Hello, ]][[string.template|${'$'}]][[variable|name]][[string|!]][[string.escape|\n]][[string|"]][[punctuation.separator|.]][[function.member|trimEnd]][[punctuation.bracket|()]]
    [[keyword|var]] [[kotlin.variable.mutable|shown]] [[punctuation.operator|=]] [[number|0]]
    [[keyword.control|while]] [[punctuation.bracket|(]][[kotlin.variable.mutable|shown]] [[punctuation.operator|<]] [[variable.parameter|times]][[punctuation.bracket|)]] [[punctuation.bracket|{]]
        [[kotlin.function.composable|Text]][[punctuation.bracket|(]][[variable|message]][[punctuation.separator|,]] [[variable.parameter|color]] [[punctuation.operator|=]] [[kotlin.type.object|Theme]][[punctuation.separator|.]][[property|accent]][[punctuation.bracket|)]]
        [[kotlin.variable.mutable|shown]][[punctuation.operator|++]]
    [[punctuation.bracket|}]]
[[punctuation.bracket|}]]

[[keyword|interface]] [[type.interface|Greeter]] [[punctuation.bracket|{]]
    [[keyword|fun]] [[function.member+function.declaration|greet]][[punctuation.bracket|(]][[variable.parameter|who]][[punctuation.operator|:]] [[type.class|String]][[punctuation.bracket|)]][[punctuation.operator|:]] [[type.class|String]]
[[punctuation.bracket|}]]

[[keyword|enum]] [[keyword|class]] [[type.enum|Tone]] [[punctuation.bracket|{]] [[constant.enum|Warm]][[punctuation.separator|,]] [[constant.enum|Formal]] [[punctuation.bracket|}]]

[[keyword|object]] [[kotlin.type.object|Defaults]] [[punctuation.bracket|{]]
    [[keyword.modifier|const]] [[keyword|val]] [[constant|MAX_TRIES]] [[punctuation.operator|=]] [[number|3]]
    [[keyword|val]] [[property|tone]] [[punctuation.operator|=]] [[type.enum|Tone]][[punctuation.separator|.]][[constant.enum|Warm]]
[[punctuation.bracket|}]]

[[keyword|class]] [[type.class|Polite]][[punctuation.bracket|(]][[keyword.modifier|private]] [[keyword|val]] [[property|tone]][[punctuation.operator|:]] [[type.enum|Tone]][[punctuation.bracket|)]] [[punctuation.operator|:]] [[type.interface|Greeter]] [[punctuation.bracket|{]]
    [[keyword.modifier|override]] [[keyword|fun]] [[function.member+function.declaration|greet]][[punctuation.bracket|(]][[variable.parameter|who]][[punctuation.operator|:]] [[type.class|String]][[punctuation.bracket|)]] [[punctuation.operator|=]] [[function.topLevel|buildString]] [[punctuation.bracket|{]]
        [[function.member|append]][[punctuation.bracket|(]][[string.char|'>']][[punctuation.bracket|)]]
        [[function.member|append]][[punctuation.bracket|(]][[string.raw|${'"'}${'"'}${'"'}]]
[[string.raw|            Good day, ]][[string.template|${'$'}]][[variable.parameter|who]][[string.raw|.]]
[[string.raw|        ${'"'}${'"'}${'"'}]][[punctuation.bracket|)]]
    [[punctuation.bracket|}]]
[[punctuation.bracket|}]]

[[keyword|val]] [[property|polite]] [[punctuation.operator|=]] [[function.constructor|Polite]][[punctuation.bracket|(]][[type.enum|Tone]][[punctuation.separator|.]][[constant.enum|Formal]][[punctuation.bracket|)]]

[[comment|// Until the analyzer answers, the lexer's own guesses show through: a capitalized word is a type,]]
[[comment|// a name before a paren is a call. Those two are what every finer class above falls back to.]]
[[keyword|val]] [[property|pending]] [[punctuation.operator|=]] [[type|Unresolved]][[punctuation.bracket|(]][[function|compute]][[punctuation.bracket|())]]

[[comment.doc|/** Resolves the first match, or null once the budget runs out. */]]
[[keyword.modifier|suspend]] [[keyword|fun]] [[punctuation.operator|<]][[type.parameter|T]][[punctuation.operator|>]] [[type.class|List]][[punctuation.operator|<]][[type.parameter|T]][[punctuation.operator|>]][[punctuation.separator|.]][[kotlin.function.extension+function.declaration|firstMatching]][[punctuation.bracket|(]][[variable.parameter|budget]][[punctuation.operator|:]] [[type.class|Int]][[punctuation.bracket|)]][[punctuation.operator|:]] [[type.parameter|T]][[punctuation.operator|?]] [[punctuation.bracket|{]]
    [[label|scan@]] [[keyword.control|for]] [[punctuation.bracket|(]][[variable|item]] [[keyword|in]] [[keyword|this]][[punctuation.bracket|)]] [[punctuation.bracket|{]]
        [[keyword.control|if]] [[punctuation.bracket|(]][[kotlin.function.suspend|matches]][[punctuation.bracket|(]][[variable|item]][[punctuation.separator|,]] [[variable.parameter|budget]][[punctuation.bracket|))]] [[keyword.control|return@]][[label|scan]] [[variable|item]]
    [[punctuation.bracket|}]]
    [[keyword.control|return]] [[keyword|null]]
[[punctuation.bracket|}]]

[[annotation|@Deprecated]][[punctuation.bracket|(]][[string|"Use Greeting instead"]][[punctuation.bracket|)]]
[[keyword|fun]] [[function.topLevel+function.declaration+modifier.deprecated|greet]][[punctuation.bracket|()]] [[punctuation.operator|=]] [[type.class|Strings]][[punctuation.separator|.]][[property.field+modifier.static|APP_NAME]]
        """.trimIndent(),
    )

    val XML = PreviewSample(
        id = "xml",
        title = "XML",
        currentLine = 6,
        caretColumn = 36,
        selectionLine = 7,
        selectionColumns = 8..34,
        errorLine = 10,
        errorColumns = 8..26,
        warningLine = 4,
        warningColumns = 4..21,
        markup = """
[[xml.prolog|<?xml version="1.0" encoding="utf-8"?>]]
[[xml.comment|<!-- A layout, coloring tags, attributes and references. -->]]
[[xml.tagDelimiter|<]][[xml.tag|LinearLayout]] [[xml.namespace|xmlns]][[xml.tagDelimiter|:]][[xml.attribute|android]]=[[xml.value|"http://schemas.android.com/apk/res/android"]]
    [[xml.namespace|android]][[xml.tagDelimiter|:]][[xml.attribute|orientation]]=[[xml.value|"vertical"]]
    [[xml.namespace|android]][[xml.tagDelimiter|:]][[xml.attribute|layout_width]]=[[xml.value|"match_parent"]][[xml.tagDelimiter|>]]

    [[xml.tagDelimiter|<]][[xml.tag|TextView]] [[xml.namespace|android]][[xml.tagDelimiter|:]][[xml.attribute|id]]=[[xml.value|"]][[xml.reference|@+id/title]][[xml.value|"]]
        [[xml.namespace|android]][[xml.tagDelimiter|:]][[xml.attribute|text]]=[[xml.value|"]][[xml.reference|@string/app_name]][[xml.value|"]]
        [[xml.namespace|android]][[xml.tagDelimiter|:]][[xml.attribute|textColor]]=[[xml.value|"]][[xml.reference|?attr/colorPrimary]][[xml.value|"]]
        [[xml.namespace|android]][[xml.tagDelimiter|:]][[xml.attribute|contentDescription]]=[[xml.value|"Tom ]][[xml.entity|&amp;]][[xml.value| Jerry"]]
        [[xml.namespace|android]][[xml.tagDelimiter|:]][[xml.attribute|padding]]=[[xml.value|"@dimen/gap"]] [[xml.tagDelimiter|/>]]

    [[xml.tagDelimiter|<]][[xml.tag|data]][[xml.tagDelimiter|>]][[xml.cdata|<![CDATA[ raw <b>markup</b> here ]]>]][[xml.tagDelimiter|</]][[xml.tag|data]][[xml.tagDelimiter|>]]

[[xml.tagDelimiter|</]][[xml.tag|LinearLayout]][[xml.tagDelimiter|>]]
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

[[markdown.listMarker|-]] Schemes carry a [[markdown.emphasis|**dark**]] and a [[markdown.emphasis|*light*]] variant.
[[markdown.listMarker|-]] Attributes fall back along a chain, so nothing is ever uncolored.
[[markdown.listMarker|-]] Run [[markdown.code|`./gradlew test`]] before tagging.

[[markdown.quote|> Imported schemes are renamed, never merged.]]

[[markdown.rule|---]]

See [[markdown.link|[the guide]]][[markdown.url|(docs/editor-color-schemes.md)]] for the file format.
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
