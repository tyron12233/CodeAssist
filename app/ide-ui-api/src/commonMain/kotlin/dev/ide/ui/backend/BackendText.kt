package dev.ide.ui.backend

/**
 * Translations of text the backend authors in English: Learn's lessons, the project templates, the settings
 * pages that have no string resources of their own. The engine that produces that text is plain JVM code with
 * no access to the UI's resources, so the UI translates it on arrival, keyed by the English text itself.
 *
 * Keyed by the source rather than by an id because the source is what is stable across every way this text
 * arrives: a lesson catalog downloaded from the server carries the same sentences as the bundled one, and a
 * sentence an author later rewrites simply falls back to English until it is translated again, instead of
 * showing a translation of what it used to say.
 *
 * A lookup tries the text as it is, then with every run of digits taken out: a settings description that
 * names this device's heap ("~512 MB") is translated once and gets the device's numbers put back in, in the
 * order they appear. The translation has to carry the same count of numbers for that to apply.
 *
 * The tables are `composeResources/files/i18n/backend-<language>.json` in `:ide-ui-resources`, one flat JSON
 * object of English to translation. `ide-core`'s `BackendTextCoverageTest` lists every string the backend can
 * show and fails when the Arabic table is missing one.
 */
class BackendText(
    /** English to translation, as the table file has them. */
    val entries: Map<String, String>,
) {
    private val exact: Map<String, String> = entries
    private val byShape: Map<String, String> = buildMap {
        // `exact`, not `entries`: inside buildMap that name is the map being built.
        for ((source, translation) in exact) {
            val sourceNumbers = NUMBER.findAll(source).count()
            if (sourceNumbers == 0 || NUMBER.findAll(translation).count() != sourceNumbers) continue
            put(shape(source), translation)
        }
    }

    val isEmpty: Boolean get() = exact.isEmpty()

    /** [text] translated, or null when the table has no entry for it. */
    fun translate(text: String): String? {
        if (text.isEmpty()) return null
        exact[text]?.let { return it }
        if (byShape.isEmpty()) return null
        val numbers = NUMBER.findAll(text).map { it.value }.toList()
        if (numbers.isEmpty()) return null
        val translation = byShape[shape(text)] ?: return null
        var i = 0
        return NUMBER.replace(translation) { m -> numbers.getOrElse(i++) { m.value } }
    }

    /** [text] translated, or [text] itself. */
    fun tr(text: String): String = translate(text) ?: text

    /** [text] translated, keeping null and blank as they are. */
    fun trOrNull(text: String?): String? = text?.let { tr(it) }

    companion object {
        val EMPTY = BackendText(emptyMap())

        private val NUMBER = Regex("\\d+")

        /** [text] with each run of digits replaced by one placeholder: what numbers-apart lookups key on. */
        fun shape(text: String): String = NUMBER.replace(text, "\u0000")

        /**
         * A table read from a flat JSON object of strings (`{"English": "translation", ...}`). Anything that
         * is not a string-to-string object is rejected with [IllegalArgumentException], so a broken file fails
         * loudly in the coverage test rather than silently translating nothing.
         */
        fun parse(json: String): BackendText = BackendText(FlatJson(json).readObject())
    }
}

/** A reader for exactly one shape of JSON: an object whose values are all strings. */
private class FlatJson(private val s: String) {
    private var i = 0

    fun readObject(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        skipWs(); expect('{'); skipWs()
        if (peek() == '}') { i++; return out }
        while (true) {
            skipWs()
            val key = readString()
            skipWs(); expect(':'); skipWs()
            out[key] = readString()
            skipWs()
            when (val c = next()) {
                ',' -> continue
                '}' -> break
                else -> fail("expected ',' or '}' but found '$c'")
            }
        }
        skipWs()
        if (i != s.length) fail("trailing content")
        return out
    }

    private fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            val c = next()
            when (c) {
                '"' -> return sb.toString()
                '\\' -> when (val e = next()) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 > s.length) fail("short \\u escape")
                        sb.append(s.substring(i, i + 4).toInt(16).toChar())
                        i += 4
                    }
                    else -> fail("bad escape \\$e")
                }
                else -> sb.append(c)
            }
        }
    }

    private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun peek(): Char = if (i < s.length) s[i] else fail("unexpected end")
    private fun next(): Char = if (i < s.length) s[i++] else fail("unexpected end")
    private fun expect(c: Char) { if (next() != c) fail("expected '$c'") }
    private fun fail(why: String): Nothing = throw IllegalArgumentException("backend text table: $why at offset $i")
}

// ---- the DTOs the backend fills with authored English, translated field by field ----

fun UiLearnCatalog.localized(t: BackendText): UiLearnCatalog = if (t.isEmpty) this else copy(
    tracks = tracks.map { it.localized(t) },
    paths = paths.map { it.copy(title = t.tr(it.title), subtitle = t.tr(it.subtitle)) },
)

fun UiLearnTrack.localized(t: BackendText): UiLearnTrack = if (t.isEmpty) this else copy(
    title = t.tr(title),
    subtitle = t.tr(subtitle),
    lessons = lessons.map { it.copy(title = t.tr(it.title), summary = t.tr(it.summary)) },
)

fun UiLesson.localized(t: BackendText): UiLesson = if (t.isEmpty) this else copy(
    title = t.tr(title),
    summary = t.tr(summary),
    steps = steps.map { it.localized(t) },
)

fun UiLessonStep.localized(t: BackendText): UiLessonStep = when (this) {
    is UiLessonStep.Concept -> copy(title = t.tr(title), blocks = blocks.map { it.localized(t) })
    is UiLessonStep.Interactive -> copy(title = t.tr(title), blocks = blocks.map { it.localized(t) }, hints = hints.map(t::tr))
    is UiLessonStep.Quiz -> copy(
        title = t.tr(title),
        prompt = t.tr(prompt),
        options = options.map(t::tr),
        explanation = t.tr(explanation),
    )
}

/** Prose and captions translated; code, layouts and previews' sources are left exactly as written. */
fun UiContentBlock.localized(t: BackendText): UiContentBlock = when (this) {
    is UiContentBlock.Text -> copy(md = t.tr(md))
    is UiContentBlock.Callout -> copy(text = t.tr(text))
    is UiContentBlock.LayoutPreview -> copy(caption = t.tr(caption))
    is UiContentBlock.ComposePreview -> copy(caption = t.tr(caption))
    else -> this
}

fun UiResumePoint.localized(t: BackendText): UiResumePoint =
    copy(trackTitle = t.tr(trackTitle), lessonTitle = t.tr(lessonTitle))

fun UiLearnAccount.localized(t: BackendText): UiLearnAccount =
    copy(badges = badges.map { it.copy(title = t.tr(it.title)) })

fun UiLearnPublicProfile.localized(t: BackendText): UiLearnPublicProfile =
    copy(badges = badges.map { it.copy(title = t.tr(it.title)) })

fun UiProjectTemplate.localized(t: BackendText): UiProjectTemplate = if (t.isEmpty) this else copy(
    displayName = t.tr(displayName),
    description = t.tr(description),
    parameters = parameters.map { p ->
        when (p) {
            is UiTemplateParam.Text -> p.copy(label = t.tr(p.label), placeholder = t.tr(p.placeholder), help = t.trOrNull(p.help))
            is UiTemplateParam.Choice -> p.copy(
                label = t.tr(p.label),
                help = t.trOrNull(p.help),
                options = p.options.map { it.copy(label = t.tr(it.label)) },
            )
            is UiTemplateParam.Toggle -> p.copy(label = t.tr(p.label), help = t.trOrNull(p.help))
        }
    },
)
