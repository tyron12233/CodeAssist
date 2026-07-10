package dev.ide.platform.log

/**
 * Global on/off for the editor hot-path timing traces — the per-stage `KotlinPerf` spans (completion /
 * analyze / highlight breakdowns) AND the per-pass wall-clock the backend logs. One flag so both turn on
 * together, read live on the engine thread. **Off by default; the only cost when off is a volatile read.**
 *
 * Two enable paths, because the two run targets differ:
 *  - **Desktop JVM** — set `-Dide.editor.perf=true` (or the legacy `-Dide.kotlin.perf=true`) on the launcher.
 *  - **On device (ART)** — no `-D` flag exists, so the "Log analysis timings" setting (Analysis page,
 *    Advanced) flips it at runtime via [applyUserPreference].
 *
 * A `-D` seed is treated as a floor the persisted setting can't turn OFF, so a desktop run started with the
 * flag keeps tracing regardless of the stored preference.
 */
object PerfTrace {
    /** The `-D` seed, remembered so an absent/false stored preference can't clear a flag set on the launcher. */
    private val syspropSeed: Boolean = runCatching {
        System.getProperty("ide.editor.perf")?.toBoolean() == true ||
            System.getProperty("ide.kotlin.perf")?.toBoolean() == true
    }.getOrNull() ?: false

    @Volatile
    var enabled: Boolean = syspropSeed

    /** Apply the persisted user toggle, OR-ed with the `-D` seed (which stays an override). */
    fun applyUserPreference(on: Boolean) {
        enabled = syspropSeed || on
    }
}
