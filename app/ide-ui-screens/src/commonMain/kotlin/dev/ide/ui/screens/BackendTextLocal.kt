package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.intl.Locale
import dev.ide.ui.backend.BackendText
import dev.ide.ui.backend.UiExerciseResult
import dev.ide.ui.backend.UiExerciseVerdict as V
import dev.ide.ui.generated.resources.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

/**
 * The translation table for the text the backend writes in English (see [BackendText]), for the language the
 * UI is showing. Empty, so everything passes through as written, until the table has loaded and for every
 * language that has none.
 *
 * Read it where the text is shown, not where it is fetched: the table loads after the first frames, and text
 * translated while it was still empty would stay English for as long as that value is kept.
 */
val LocalBackendText = staticCompositionLocalOf { BackendText.EMPTY }

/** The table for the UI's current language, loaded once per process and language. */
@Composable
fun rememberBackendText(): BackendText {
    val language = Locale.current.language.lowercase()
    val table by produceState(BackendTextTables.cached(language) ?: BackendText.EMPTY, language) {
        value = BackendTextTables.load(language)
    }
    return table
}

internal object BackendTextTables {
    private val lock = Mutex()
    private val loaded = HashMap<String, BackendText>()

    fun cached(language: String): BackendText? = loaded[language]

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(language: String): BackendText = lock.withLock {
        loaded[language]?.let { return@withLock it }
        // English is the source; a language with no table (or a table that fails to read) shows it unchanged.
        val table = if (language == "en") BackendText.EMPTY else runCatching {
            val parsed = BackendText.parse(Res.readBytes("files/i18n/backend-$language.json").decodeToString())
            if (language in RTL_LANGUAGES) BackendText(parsed.entries.mapValues { isolateLatinCalls(it.value) }) else parsed
        }.getOrDefault(BackendText.EMPTY)
        loaded[language] = table
        table
    }
}

private val RTL_LANGUAGES = setOf("ar", "fa", "he", "iw", "ur")

/** A call or array type written in plain text (`main()`, `paintComponent()`, `int[]`), outside backticks. */
private val LATIN_CALL = Regex("(?<![\\w`\u2066])([A-Za-z_][\\w.]*(?:\\(\\)|\\[\\]))")

/**
 * [text] with each call-like name outside backticks wrapped in a left-to-right isolate (U+2066 … U+2069). In a
 * right-to-left sentence the bidi algorithm otherwise sends a trailing `()` to the far side of its name, so
 * `main()` reads `()main`. Backticked code is isolated where it is rendered ([inlineMarkup]).
 */
internal fun isolateLatinCalls(text: String): String {
    if ('(' !in text && '[' !in text) return text
    val parts = text.split('`')
    return parts.mapIndexed { i, part ->
        if (i % 2 == 1) part else LATIN_CALL.replace(part) { "\u2066${it.value}\u2069" }
    }.joinToString("`")
}

/** A checked exercise's verdict in the UI's language; the backend's own English [UiExerciseResult.message] for
 *  a result without a known verdict. */
@Composable
internal fun exerciseMessage(r: UiExerciseResult): String {
    val t = LocalBackendText.current
    val list = stringResource(Res.string.learn_v_list_sep) + " "
    val rules = stringResource(Res.string.learn_v_rule_sep) + " "
    // What a program printed, or must print, read left to right even inside an Arabic verdict: `Hello, Kotlin!`
    // otherwise shows as `!Hello, Kotlin`. An isolate ends at a line break, so each line gets its own.
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    fun ltr(text: String) = if (!rtl) text else text.lines().joinToString("\n") { if (it.isEmpty()) it else "\u2066$it\u2069" }
    fun code(items: List<String>) = items.joinToString(list) { ltr("`$it`") }
    val a = r.args
    fun arg(i: Int) = ltr(a.getOrNull(i).orEmpty())
    return when (r.verdict) {
        V.CORRECT -> stringResource(Res.string.learn_v_correct)
        V.EXPECTED_OUTPUT -> stringResource(Res.string.learn_v_expected_output) + "\n" + arg(0)
        V.MUST_INCLUDE -> stringResource(Res.string.learn_v_must_include, a.joinToString(list) { ltr(it) })
        V.RETRY -> stringResource(Res.string.learn_v_retry)
        V.USE_SOURCE -> stringResource(Res.string.learn_v_use_source, code(a))
        V.NO_COMPILE -> stringResource(Res.string.learn_v_no_compile)
        V.NO_RUN -> listOf(stringResource(Res.string.learn_v_no_run), arg(0)).joinToString(" ").trim()
        V.TEST_FAILED_EXPECTED, V.TEST_FAILED_INCLUDE -> buildString {
            append(stringResource(Res.string.learn_v_test_failed, a.getOrElse(0) { "?" }, a.getOrElse(1) { "?" }))
            append('\n').append(stringResource(Res.string.learn_v_input))
            append('\n').append(if (a.getOrNull(2).isNullOrBlank()) stringResourceNoInput() else arg(2))
            append('\n')
            if (r.verdict == V.TEST_FAILED_EXPECTED) {
                append(stringResource(Res.string.learn_v_expected_output_label)).append('\n').append(arg(3))
            } else {
                append(stringResource(Res.string.learn_v_should_include, a.drop(3).joinToString(list) { ltr(it) }))
            }
        }
        V.TESTS_PASSED -> stringResource(Res.string.learn_v_tests_passed, a.firstOrNull().orEmpty())
        V.TESTS_PASS_USE_SOURCE -> stringResource(Res.string.learn_v_tests_pass_use_source, a.firstOrNull().orEmpty(), code(a.drop(1)))
        V.NO_EXERCISE -> stringResource(Res.string.learn_v_no_exercise)
        V.OPEN_FROM_LEARN -> stringResource(Res.string.learn_v_open_from_learn)
        V.RUNNER_UNSUPPORTED -> stringResource(Res.string.learn_v_runner_unsupported)
        V.RUNNER_UNAVAILABLE -> stringResource(Res.string.learn_v_runner_unavailable)
        V.RUNNER_PREPARE_FAILED -> stringResource(Res.string.learn_v_runner_prepare_failed, arg(0))
        V.SAVE_FAILED -> stringResource(Res.string.learn_v_save_failed, arg(0))
        V.RUNNER_FAILED -> stringResource(Res.string.learn_v_runner_failed, arg(0))
        V.XML_MALFORMED -> stringResource(Res.string.learn_v_xml_malformed)
        V.LAYOUT_NEEDS -> stringResource(Res.string.learn_v_layout_needs, a.joinToString(rules) { t.tr(it) })
        V.LAYOUT_NO_RENDER -> stringResource(Res.string.learn_v_layout_no_render)
        V.LAYOUT_PROBLEM -> stringResource(Res.string.learn_v_layout_problem)
        V.IDE_DONE -> stringResource(Res.string.learn_v_ide_done)
        V.IDE_TODO -> stringResource(Res.string.learn_v_ide_todo, a.joinToString(rules) { t.tr(it) })
        else -> r.message
    }
}

@Composable
private fun stringResourceNoInput(): String = stringResource(Res.string.learn_v_no_input)

/**
 * A checked exercise's output in the UI's language. A layout or workspace exercise's output is a checklist of
 * its rules (`✓ a Button with id signIn`), whose labels are authored text; a program's output is the program's
 * and is shown exactly as it printed.
 */
@Composable
internal fun exerciseOutput(r: UiExerciseResult): String {
    val t = LocalBackendText.current
    if (!isChecklist(r) || t.isEmpty) return r.output
    return r.output.lines().joinToString("\n") { line ->
        val mark = line.take(2)
        if (mark == "✓ " || mark == "✗ ") mark + t.tr(line.drop(2)) else line
    }
}

/** Whether [r]'s output is a checklist of the exercise's rules (prose) rather than what a program printed. */
internal fun isChecklist(r: UiExerciseResult): Boolean =
    r.verdict in setOf(V.LAYOUT_NEEDS, V.LAYOUT_NO_RENDER, V.LAYOUT_PROBLEM, V.IDE_DONE, V.IDE_TODO) ||
        (r.verdict == V.CORRECT && r.output.isNotBlank() &&
            r.output.lines().all { it.isBlank() || it.startsWith("✓ ") || it.startsWith("✗ ") })

/** [value] put through [localize] with the current table, again whenever either changes. */
@Composable
internal fun <T> rememberLocalized(value: T, localize: (T, BackendText) -> T): T {
    val t = LocalBackendText.current
    return androidx.compose.runtime.remember(value, t) { localize(value, t) }
}
