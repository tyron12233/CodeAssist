package dev.ide.core.analysis

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.AnalyzerId
import dev.ide.analysis.AnalyzerTier
import dev.ide.analysis.DiagnosticSink
import dev.ide.analysis.FileAnalyzer
import dev.ide.core.plugins.PluginManifestToml
import dev.ide.core.plugins.PluginProject
import dev.ide.index.ClassNameIndex
import dev.ide.index.ClassNameValue
import dev.ide.index.SubtypeIndex
import dev.ide.index.SubtypeValue
import dev.ide.lang.LanguageId
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.model.Module
import dev.ide.plugin.PLUGIN_API_VERSION
import dev.ide.plugin.PluginCapabilities
import dev.ide.plugin.PluginVersions
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Editor checks for a plugin's packaged manifest, `res/raw/codeassist_plugin.toml`.
 *
 * The manifest is read at IDE startup by whatever build the user installs the plugin into, so every mistake
 * in it surfaces at the worst possible moment: on someone else's device, as a row in their Plugins screen or
 * as no row at all. These are the same checks that pass or fail there, run while the file is being edited.
 *
 * Deliberately reports only what is certain. The entry-point checks need the workspace index, so they are
 * skipped entirely until it is ready rather than claiming a class is missing while indexing is still
 * running, and the marker-activity check is skipped when the module's layout does not say where the Android
 * manifest is. A false error on a file this small would be worse than a missing one.
 *
 * `.toml` has no language of its own, so this is registered against plain text and selects the file by name.
 */
class PluginManifestAnalyzer(
    /** The running IDE's version, checked against `minHostVersion`. Null skips that check. */
    private val hostVersion: () -> String?,
) : FileAnalyzer {

    override val id = AnalyzerId("codeassistPluginManifest")
    override val displayName = "CodeAssist plugin manifest problems"
    override val languages = setOf(LanguageId("text"))
    override val defaultSeverity = Severity.ERROR

    /** Entry-point resolution reads the index, so this runs on the settled buffer, not per keystroke. */
    override val tier = AnalyzerTier.SEMANTIC
    override val interestedIn: Set<NodeKind>? = null // whole-file, invoked once

    override fun analyze(target: AnalysisTarget, sink: DiagnosticSink) {
        val path = Paths.get(target.file.path)
        if (path.fileName?.toString() != PluginProject.MANIFEST_NAME) return

        val text = target.parsed.text().toString()
        val ranges = KeyRanges(text)

        val manifest = try {
            PluginManifestToml.parse(text)
        } catch (e: IllegalArgumentException) {
            // The parser's message is already user-facing; point at the key it names when it names one.
            val message = e.message.orEmpty()
            sink.report(ranges.forMessage(message), Severity.ERROR, message, CODE)
            return
        }

        if (manifest.apiVersion != PLUGIN_API_VERSION) {
            sink.report(
                ranges.value("apiVersion"),
                Severity.ERROR,
                "Built for plugin API ${manifest.apiVersion}; this IDE loads API $PLUGIN_API_VERSION. " +
                    "A plugin declaring a different API version is rejected at load.",
                CODE,
            )
        }

        val host = hostVersion()
        val min = manifest.minHostVersion
        if (min != null && !PluginVersions.satisfies(host, min)) {
            sink.report(
                ranges.value("minHostVersion"),
                Severity.ERROR,
                "Requires CodeAssist $min or newer; this IDE is $host, so it would not load here.",
                CODE,
            )
        }

        checkEntryPoints(manifest.entryPoints, PLUGIN_INTERFACE, "entryPoints", target, ranges, sink)
        checkEntryPoints(manifest.uiEntryPoints, UI_PLUGIN_INTERFACE, "uiEntryPoints", target, ranges, sink)
        checkCapabilities(manifest, ranges, sink)
        checkMarkerActivity(target.module, path, ranges, sink)
    }

    /**
     * What the manifest claims has to be something the plugin can actually do, because this list is what the
     * user is shown when they decide whether to allow the plugin at all.
     *
     * All of it is decidable from the manifest itself, so nothing here waits on the index. A capability the
     * IDE does not recognise is shown to that user verbatim, and one whose facet the plugin does not declare
     * is shown for something the plugin has no way to deliver. Both are warnings rather than errors: the
     * plugin still loads, and the cost is a consent screen that misdescribes it.
     */
    private fun checkCapabilities(
        manifest: dev.ide.plugin.PluginManifest,
        ranges: KeyRanges,
        sink: DiagnosticSink,
    ) {
        if (manifest.capabilities.isEmpty()) {
            // The converse: a plugin that contributes UI and says nothing about it. The consent gate then
            // describes a plugin with panels as doing nothing in particular.
            if (manifest.uiEntryPoints.isNotEmpty()) {
                sink.report(
                    ranges.value("uiEntryPoints"),
                    Severity.WARNING,
                    "This plugin contributes UI but declares no capabilities, so the consent screen will " +
                        "not say so. Add what it contributes, e.g. '${PluginCapabilities.UI_TOOL_WINDOW}'.",
                    CODE,
                )
            }
            return
        }

        val range = ranges.value("capabilities")
        for (capability in manifest.capabilities) {
            if (capability !in PluginCapabilities.KNOWN) {
                sink.report(
                    range,
                    Severity.WARNING,
                    "Unknown capability '$capability'; it is shown to the user exactly as written. " +
                        "Known: ${PluginCapabilities.KNOWN.joinToString(", ")}.",
                    CODE,
                )
                continue
            }
            if (capability in PluginCapabilities.NEEDS_UI_FACET && manifest.uiEntryPoints.isEmpty()) {
                sink.report(
                    range,
                    Severity.WARNING,
                    "'$capability' needs a UI facet, and 'uiEntryPoints' is empty, so the plugin cannot " +
                        "contribute one.",
                    CODE,
                )
            }
            if (capability in PluginCapabilities.NEEDS_ENGINE_FACET && manifest.entryPoints.isEmpty()) {
                sink.report(
                    range,
                    Severity.WARNING,
                    "'$capability' needs an engine facet, and 'entryPoints' is empty, so the plugin cannot " +
                        "contribute one.",
                    CODE,
                )
            }
        }
    }

    /**
     * Every declared entry point has to name a class in the project that implements [supertype] (`Plugin`
     * for `entryPoints`, `UiPlugin` for `uiEntryPoints`), since the loader instantiates it by exactly that
     * name and casts it. Both halves need the index, so nothing is reported until it holds queryable data.
     */
    private fun checkEntryPoints(
        entryPoints: List<String>,
        supertype: String,
        key: String,
        target: AnalysisTarget,
        ranges: KeyRanges,
        sink: DiagnosticSink,
    ) {
        if (entryPoints.isEmpty()) return
        if (!target.index.status.ready) return
        val range = ranges.value(key)

        val pluginSubtypes = SubtypeIndex.ALL
            .flatMap { target.index.exact<SubtypeValue>(it, SubtypeIndex.key(supertype)).toList() }
            .mapTo(HashSet()) { it.fqn }

        for (fqcn in entryPoints) {
            target.checkCanceled()
            val simpleName = fqcn.substringAfterLast('.')
            val declared = ClassNameIndex.ALL
                .flatMap { target.index.exact<ClassNameValue>(it, simpleName).toList() }
                .any { it.fqn == fqcn }

            if (!declared) {
                sink.report(
                    range,
                    Severity.ERROR,
                    "No class '$fqcn' in this project. The IDE loads the entry point by this exact name, " +
                        "so a rename here has to match the class.",
                    CODE,
                )
                continue
            }
            if (fqcn !in pluginSubtypes) {
                sink.report(
                    range,
                    Severity.ERROR,
                    "'$fqcn' does not implement $supertype, so the IDE cannot use it as an entry point.",
                    CODE,
                )
            }
        }
    }

    /**
     * Without the marker activity the package manager query never returns this app, so the plugin is not
     * merely broken: it is invisible, with nothing in the Plugins screen to explain why. Worth an error on
     * the manifest even though the mistake is in the Android manifest next door.
     */
    private fun checkMarkerActivity(module: Module, manifestPath: Path, ranges: KeyRanges, sink: DiagnosticSink) {
        val androidManifest = PluginProject.androidManifestBeside(module, manifestPath) ?: return
        val xml = runCatching { Files.readString(androidManifest) }.getOrNull() ?: return

        val missing = buildList {
            val action = PluginProject.PLUGIN_ACTION
            val meta = PluginProject.META_MANIFEST
            if (action !in xml) add("an activity with the '$action' intent filter")
            if (meta !in xml) add("a '$meta' meta-data entry pointing at this file")
        }
        if (missing.isEmpty()) return
        sink.report(
            ranges.header(),
            Severity.ERROR,
            "${androidManifest.fileName} is missing ${missing.joinToString(" and ")}. " +
                "Without it the IDE cannot discover this plugin at all.",
            CODE,
        )
    }

    /**
     * Where each top-level `key = value` sits, so a finding underlines the value it is about instead of the
     * whole file. Keys are matched at the start of a line, which is all the manifest's flat shape needs.
     */
    private class KeyRanges(private val text: String) {

        fun value(key: String): TextRange {
            val match = Regex("(?m)^[ \\t]*${Regex.escape(key)}[ \\t]*=[ \\t]*").find(text) ?: return header()
            val start = match.range.last + 1
            var end = start
            while (end < text.length && text[end] != '\n') end++
            // Trim a trailing comment and whitespace so the squiggle covers the value alone.
            val line = text.substring(start, end)
            val trimmed = line.substringBefore('#').trimEnd()
            end = start + trimmed.length
            return TextRange(start, end.coerceAtLeast(minOf(start + 1, text.length)))
        }

        /** The `[plugin]` table header, or the first line, for a finding about the file as a whole. */
        fun header(): TextRange {
            val match = Regex("(?m)^[ \\t]*\\[plugin][ \\t]*$").find(text)
            if (match != null) return TextRange(match.range.first, match.range.last + 1)
            val end = text.indexOf('\n').let { if (it < 0) text.length else it }
            return TextRange(0, end.coerceAtLeast(minOf(1, text.length)))
        }

        /** The key a parser message names, so a malformed value is underlined rather than the header. */
        fun forMessage(message: String): TextRange = when {
            "'id'" in message || "plugin id" in message -> value("id")
            // "declares no 'entryPoints' or 'uiEntryPoints'" names both and has neither to underline, so it
            // falls through to the header; a message about one existing key still points at that key.
            "'uiEntryPoints'" in message && "'entryPoints'" !in message -> value("uiEntryPoints")
            "entryPoints" in message -> value("entryPoints")
            else -> header()
        }
    }

    private companion object {
        const val PLUGIN_INTERFACE = "dev.ide.plugin.Plugin"
        const val UI_PLUGIN_INTERFACE = "dev.ide.plugin.ui.UiPlugin"
        const val CODE = "codeassistPlugin"
    }
}
