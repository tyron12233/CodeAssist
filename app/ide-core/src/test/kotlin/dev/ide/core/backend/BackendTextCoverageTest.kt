package dev.ide.core.backend

import dev.ide.core.IdeServicesBackend
import dev.ide.core.project.ProjectManager
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.BackendText
import dev.ide.ui.backend.UiContentBlock
import dev.ide.ui.backend.UiSettingControl
import dev.ide.ui.backend.UiTemplateParam
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every piece of English the backend can put on screen (the Learn catalog, the project templates, the settings
 * pages) has a translation in the Arabic table the UI reads ([BackendText]).
 *
 * The full list is written to `build/i18n/backend-text-sources.json` and what the Arabic table lacks to
 * `build/i18n/backend-ar-missing.json`, which is the file to translate when this fails: add each entry, English
 * to Arabic, to `:ide-ui-resources` `composeResources/files/i18n/backend-ar.json`. Code in backticks stays
 * exactly as written; the test checks that too.
 */
class BackendTextCoverageTest {

    private val arabicTable: Path =
        Paths.get("../ide-ui-resources/src/commonMain/composeResources/files/i18n/backend-ar.json")

    @Test
    fun theArabicTableCoversEveryBackendString() {
        val sources = backendStrings()
        val out = Paths.get("build/i18n")
        Files.createDirectories(out)
        Files.write(out.resolve("backend-text-sources.json"), jsonList(sources).toByteArray(Charsets.UTF_8))

        val table = if (Files.isRegularFile(arabicTable)) {
            BackendText.parse(String(Files.readAllBytes(arabicTable), Charsets.UTF_8))
        } else BackendText.EMPTY
        val missing = sources.filter { table.translate(it) == null }
        Files.write(out.resolve("backend-ar-missing.json"), jsonList(missing).toByteArray(Charsets.UTF_8))
        if (missing.isNotEmpty()) {
            fail(
                "${missing.size} of ${sources.size} backend strings have no Arabic translation " +
                    "(listed in ${out.toAbsolutePath().resolve("backend-ar-missing.json")}), e.g.:\n" +
                    missing.take(10).joinToString("\n") { "  " + it.take(120).replace("\n", "\\n") },
            )
        }
    }

    @Test
    fun translationsKeepTheirCodeAndMarkup() {
        if (!Files.isRegularFile(arabicTable)) return
        val entries = BackendText.parse(String(Files.readAllBytes(arabicTable), Charsets.UTF_8)).entries
        val broken = entries.entries.mapNotNull { (en, ar) ->
            val why = when {
                ar.isBlank() -> "empty"
                codeSpans(en).sorted() != codeSpans(ar).sorted() -> "code in backticks changed: ${codeSpans(en)} vs ${codeSpans(ar)}"
                en.split("**").size != ar.split("**").size -> "bold markers (**) differ"
                en.count { it == '\n' } > 0 && ar.count { it == '\n' } == 0 -> "line breaks lost"
                else -> null
            }
            why?.let { "${en.take(80).replace("\n", "\\n")}: $it" }
        }
        assertTrue(broken.isEmpty(), "${broken.size} translations break their source's code or markup:\n" + broken.take(20).joinToString("\n"))
    }

    // ---- what the backend can show ----

    private fun backendStrings(): List<String> {
        val out = LinkedHashSet<String>()
        fun add(s: String?) { if (!s.isNullOrBlank()) out += s }

        // Learn: the bundled catalog, every field the UI shows as prose. Code, starter code and solutions stay.
        val catalog = LearnContent.catalog
        catalog.paths.forEach { add(it.title); add(it.subtitle) }
        catalog.tracks.forEach { t ->
            add(t.title); add(t.subtitle); add(t.category)
            t.lessons.forEach { l ->
                add(l.title); add(l.summary)
                l.steps.forEach { s ->
                    add(s.title)
                    when (s) {
                        is LearnStepDef.Concept -> s.blocks.forEach { add(blockText(it)) }
                        is LearnStepDef.Interactive -> {
                            s.blocks.forEach { add(blockText(it)) }
                            s.hints.forEach(::add)
                            s.check.layout?.views?.forEach { add(it.label) }
                            s.check.ide.forEach { add(it.label) }
                        }
                        is LearnStepDef.Quiz -> { add(s.prompt); s.options.forEach(::add); add(s.explanation) }
                    }
                }
            }
        }
        // The layout checker's own checklist line (LearnLayoutCheck).
        add("renders cleanly")

        withTempDir("backend-text") { root ->
            val manager = ProjectManager.desktop(root.resolve("projects"))
            manager.create("java-library", mapOf("name" to "Lib", "packageName" to "com.acme.lib")).use { ide ->
                val backend = IdeServicesBackend(ide)
                // Templates: the gallery's names, descriptions and form, and the category labels it groups by.
                backend.projects.projectTemplates().forEach { t ->
                    add(t.displayName); add(t.description); add(t.category)
                    t.parameters.forEach { p ->
                        add(p.label); add(p.help)
                        when (p) {
                            is UiTemplateParam.Text -> add(p.placeholder)
                            is UiTemplateParam.Choice -> p.options.forEach { add(it.label) }
                            is UiTemplateParam.Toggle -> Unit
                        }
                    }
                }
                add("Other")
                // Settings: every page, built-in or contributed by a bundled plugin, with a project open.
                backend.settings.settingsPages().forEach { page ->
                    add(page.title)
                    page.controls.forEach { c ->
                        add(c.title); add(c.description); add(c.group)
                        when (c) {
                            is UiSettingControl.Choice -> c.options.forEach { add(it.label) }
                            is UiSettingControl.Action -> add(c.buttonLabel)
                            is UiSettingControl.Slider -> c.unit?.takeIf { u -> u.any(Char::isLetter) }?.let(::add)
                            else -> Unit
                        }
                    }
                }
            }
        }
        // Build Runtime descriptions that only an Android device reaches (SettingsBackend.buildRuntimeControls),
        // with sample numbers: the table matches them with the device's own numbers put back in.
        ANDROID_ONLY.forEach(::add)
        return out.toList()
    }

    private fun blockText(b: UiContentBlock): String? = when (b) {
        is UiContentBlock.Text -> b.md
        is UiContentBlock.Callout -> b.text
        is UiContentBlock.LayoutPreview -> b.caption
        is UiContentBlock.ComposePreview -> b.caption
        else -> null
    }

    // ---- helpers ----

    private fun codeSpans(s: String): List<String> = Regex("`([^`]*)`").findAll(s).map { it.groupValues[1] }.toList()

    private fun jsonList(items: List<String>): String = items.joinToString(",\n  ", "[\n  ", "\n]\n") { quote(it) }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    private companion object {
        val ANDROID_ONLY = listOf(
            "R8 runs inside the IDE, capped at this app's memory limit (~512 MB). Pick Forked VM to give R8 more by running it in a separate VM.",
            "This device can't run R8 in a separate VM, so it runs in-process (~512 MB). Large apps may run out of memory; there's nothing to tune here.",
            "Forked VM (default) runs R8 in a separate VM with more memory than this app's ~512 MB limit, so large apps don't run out of memory; it falls back to in-process if the device can't. Android only.",
            "Heap for R8's forked VM. This device's limit is 2048 MB.",
            "Heap for R8's forked VM. This device's limit is 2048 MB. ⚠ Your saved value (3072 MB) is above the device limit; R8 will use 2048 MB.",
            "Moves large dexing steps off the IDE's heap into a separate VM on a clean build. Inactive while R8 execution is In-process (or the device can't fork a VM) — everything dexes in-process then.",
            "Caps how many separate dexing VMs run at once. Inactive while R8 execution is In-process (or the device can't fork a VM) — everything dexes in-process then.",
            // The Build Runtime page's own descriptors, listed on a device only (SettingsBackend.buildRuntimeControls).
            "Low memory mode",
            "Use smaller caches, skip background warm-ups, and free memory whenever the IDE leaves the screen, so the system is less likely to close it. Auto turns this on for devices with little memory. Takes effect the next time you open a project.",
            "Auto",
            "On",
            "Off",
            "Download prebuilt libraries",
            "Download libraries that are already converted for Android and indexed for the editor, instead of doing both on this device. A new project builds several times faster and its code completion is ready sooner. Wi-Fi only does that work on this device while you're on mobile data. Applies on the next build.",
            "Automatically",
            "Wi-Fi only",
            "Forward app logs",
            "On a debug build, inject a small log bridge into your app so its logs (logcat, println, crashes) stream to the Logcat tab. Debug builds only — release builds are never modified. Applies on the next build.",
            "Kotlin compiler execution",
            "Keep a Kotlin compiler VM running alongside the IDE so compiles get more memory than the app is allowed and don't compete with the editor. Off = compile inside the IDE process. Applies on the next build.",
            "Compiler VM",
            "In-process",
            "Kotlin compiler VM heap",
            "Memory the Kotlin compiler VM may use. The device may grant less, in which case the largest heap it can back is used.",
            "Kotlin compiler VMs",
            "How many Kotlin compiler VMs run at once. Each stays resident for the session, so raise this only for a project whose modules compile in parallel.",
        )
    }
}
