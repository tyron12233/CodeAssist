package dev.ide.core.services

import dev.ide.core.EngineContext
import dev.ide.index.EntryPointIndex
import dev.ide.index.EntryPointValue
import dev.ide.lang.jdt.index.JavaMainScan
import dev.ide.lang.kotlin.index.KotlinMainScan
import dev.ide.model.Module
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import kotlin.io.path.readText

/** A runnable console entry point: the [mainClass] the runner launches, and whether it must be [instance]-invoked
 *  (no static `main` — the runner constructs the class with its no-arg constructor and calls the instance method). */
internal data class RunTarget(val mainClass: String, val instance: Boolean)

/**
 * Finds runnable `main` entry points for a console (Java/Kotlin) module. Backed by the `java.mains`/`kotlin.mains`
 * entry-point indexes (a real parse per source file, done once at index time — see [JavaMainScan]/[KotlinMainScan]),
 * queried by [EntryPointIndex.KEY] and scoped to the module's own source roots. While the index is still building,
 * it falls back to an on-demand semantic scan of the same detectors so Run works immediately after opening a project.
 */
internal object MainClassDetection {

    /** The module's runnable entry points, Java first then Kotlin (so the default/first target is deterministic
     *  when a module mixes both), de-duplicated by FQN. */
    fun detect(ctx: EngineContext, module: Module): List<RunTarget> {
        val roots = ctx.sourceRoots(module).map { it.toAbsolutePath().normalize() }
        if (roots.isEmpty()) return emptyList()
        val fromIndex = fromIndex(ctx, roots)
        // Trust the index once it's ready (an empty result then means the module genuinely has no main); only
        // pay for a direct scan while it's still building, so the Run picker isn't empty right after open.
        if (fromIndex.isNotEmpty() || ctx.indexService.status.ready) return fromIndex
        return directScan(roots)
    }

    private fun fromIndex(ctx: EngineContext, roots: List<Path>): List<RunTarget> {
        val idx = ctx.indexService
        val hits = idx.exact<EntryPointValue>(EntryPointIndex.JAVA, EntryPointIndex.KEY).toList() +
            idx.exact<EntryPointValue>(EntryPointIndex.KOTLIN, EntryPointIndex.KEY).toList()
        val out = LinkedHashMap<String, RunTarget>()
        for (v in hits) {
            val path = idx.filePath(v.fileId) ?: continue
            val p = runCatching { Paths.get(path).toAbsolutePath().normalize() }.getOrNull() ?: continue
            if (roots.none { p.startsWith(it) }) continue
            merge(out, v.fqn, v.instance)
        }
        return out.values.toList()
    }

    /**
     * The module's runnable entry points found by scanning its sources on disk RIGHT NOW, bypassing the index.
     * Used by the programmatic run-and-capture path ([BuildService.runAndCapture]) — the Learn exercise checker
     * writes the module's `Main` straight to disk on each run (not through the save→reindex path), so the
     * persisted entry-point index can name a stale FQN (e.g. the template's packaged `com.example.app.Main`
     * after the learner's default-package `Main` replaced it → "Could not find or load main class …"). The
     * freshly written file is authoritative, so we scan it directly rather than trust the index.
     */
    fun detectLive(ctx: EngineContext, module: Module): List<RunTarget> {
        val roots = ctx.sourceRoots(module).map { it.toAbsolutePath().normalize() }
        if (roots.isEmpty()) return emptyList()
        return directScan(roots)
    }

    /** Cold-start fallback: parse the module's sources with the SAME detectors the indexes use. */
    private fun directScan(roots: List<Path>): List<RunTarget> {
        val out = LinkedHashMap<String, RunTarget>()
        scan(roots, ".java") { text, _ -> JavaMainScan.scan(text) }.forEach { merge(out, it.first, it.second) }
        scan(roots, ".kt") { text, name -> KotlinMainScan.scan(name, text) }.forEach { merge(out, it.first, it.second) }
        return out.values.toList()
    }

    private fun scan(
        roots: List<Path>, ext: String, detect: (text: String, name: String) -> List<Pair<String, Boolean>>,
    ): List<Pair<String, Boolean>> {
        val found = ArrayList<Pair<String, Boolean>>()
        for (root in roots) {
            if (!Files.isDirectory(root)) continue
            val files = runCatching {
                Files.walk(root).use { s -> s.filter { it.toString().endsWith(ext) }.collect(Collectors.toList()) }
            }.getOrDefault(emptyList())
            for (f in files) {
                val text = runCatching { f.readText() }.getOrNull() ?: continue
                found += detect(text, f.fileName.toString())
            }
        }
        return found
    }

    /** Keep one target per FQN, preferring a static invocation over an instance one if both were reported. */
    private fun merge(out: MutableMap<String, RunTarget>, fqn: String, instance: Boolean) {
        val existing = out[fqn]
        if (existing == null || (existing.instance && !instance)) out[fqn] = RunTarget(fqn, instance)
    }
}

/** True when [module] is a console-runnable (non-Android) module — the ones the "Run" configuration
 *  (main class) applies to. Android app/library modules run through the APK pipeline instead. */
internal fun isConsoleRunModule(module: Module): Boolean =
    module.type.id != "android-app" && module.type.id != "android-lib"

/** The user-configured main-class override for [module]'s console Run (`.platform/settings.properties`),
 *  or null when unset/blank (auto-detect). */
internal fun EngineContext.mainClassOverride(module: Module): String? =
    projectPref(mainClassPrefKey(module))?.trim()?.takeIf { it.isNotEmpty() }

/** Set (or, with a blank [value], clear) the main-class override for [module]'s console Run. */
internal fun EngineContext.setMainClassOverride(module: Module, value: String) =
    setProjectPref(mainClassPrefKey(module), value.trim())

private fun mainClassPrefKey(module: Module) = "module.mainClass.${module.id.value}"
