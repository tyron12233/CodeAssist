package dev.ide.core.plugins

/**
 * Keeps an installed plugin's crash from becoming the IDE's crash loop.
 *
 * Installed plugins run inside the IDE's own process, so their uncaught throw on the main thread is the IDE
 * dying, and one that throws on every launch would leave the user unable to reach the screen that turns it
 * off. Two answers, both persisted through the app preferences so they reach the NEXT launch:
 *
 * - **Blame.** A fatal crash whose stack runs through a plugin's code ([PluginBlame]) turns that plugin off
 *   (into the ordinary disabled set, so its row and its toggle are the familiar ones) and records the version
 *   that crashed. An update lifts it; so does the user turning it back on.
 * - **Safe mode.** Fatal crashes within [QUICK_CRASH_MS] of start that no plugin can be blamed for are
 *   counted; at [SAFE_MODE_AFTER] in a row the next launch loads no installed plugin at all. The count clears
 *   once a launch has lived [STABLE_AFTER_MS], and after a safe-mode launch, so the one after that tries again.
 *
 * What the user should be told ("X crashed and was turned off", "started without plugins") is left as a
 * [Notice] for the next launch to post, because the launch that crashed has no screen left to say it on.
 *
 * Pure over [Prefs] so it is testable without a device; the host wires the clock and the uptime.
 */
class PluginCrashGuard(private val prefs: Prefs) {

    /** The app preference store, as the guard needs it. */
    interface Prefs {
        fun get(key: String): String?
        fun set(values: Map<String, String>)
    }

    /** Something to tell the user on the next launch. [pluginId] is null for safe mode. */
    data class Notice(val pluginId: String?, val title: String, val body: String)

    /** Installed plugins turned off for crashing, by id, with the version that crashed. */
    fun crashSuspended(): Map<String, String> = decode(prefs.get(CRASHED_KEY))

    /** Whether this launch should load no installed plugin: the last [SAFE_MODE_AFTER] launches died young. */
    fun safeModeRequested(): Boolean = quickCrashes() >= SAFE_MODE_AFTER

    /**
     * A crash the process cannot survive. [blamed] is the plugin [PluginBlame] named (with its version and
     * display name), or null when no plugin frame was on the stack. [uptimeMs] is how long this process ran.
     * [disabled] is the current disabled set, which the blamed plugin joins.
     */
    fun onFatalCrash(blamed: Blamed?, uptimeMs: Long, disabled: Set<String>) {
        if (blamed != null) {
            prefs.set(
                mapOf(
                    DISABLED_KEY to (disabled + blamed.id).sorted().joinToString(","),
                    CRASHED_KEY to encode(crashSuspended() + (blamed.id to blamed.version)),
                    NOTICE_KEY to encodeNotice(
                        Notice(
                            blamed.id,
                            "${blamed.name} was turned off",
                            "It crashed CodeAssist. It stays off until a new version is installed, or until you turn it back on in Plugins.",
                        ),
                    ),
                    // A crash with a culprit is not the unexplained kind safe mode exists for.
                    QUICK_CRASHES_KEY to "0",
                ),
            )
        } else if (uptimeMs < QUICK_CRASH_MS) {
            prefs.set(mapOf(QUICK_CRASHES_KEY to (quickCrashes() + 1).toString()))
        }
    }

    /** A crash-suspended plugin [id] is running again (updated, or turned back on): forget why it was off. */
    fun forget(ids: Collection<String>) {
        val now = crashSuspended()
        if (ids.none { it in now }) return
        prefs.set(mapOf(CRASHED_KEY to encode(now - ids.toSet())))
    }

    /**
     * Called once by the UI process after the plugins loaded. Clears the suspensions an update lifted, and
     * when this launch ran in safe mode, resets the count (the next launch tries the plugins again) and
     * leaves the notice that says why they are missing.
     */
    fun afterLoad(lifted: Set<String>, safeModeApplied: Boolean) {
        forget(lifted)
        if (safeModeRequested()) {
            val values = mutableMapOf(QUICK_CRASHES_KEY to "0")
            if (safeModeApplied) {
                values[NOTICE_KEY] = encodeNotice(
                    Notice(
                        null,
                        "Started without plugins",
                        "CodeAssist closed unexpectedly right after starting several times, so this time it started " +
                            "without your installed plugins. They load again on the next start; turn off the one " +
                            "causing it in Plugins.",
                    ),
                )
            }
            prefs.set(values)
        }
    }

    /** This launch has run long enough to count as a good one: unexplained early crashes start counting again. */
    fun markStable() {
        if (quickCrashes() != 0) prefs.set(mapOf(QUICK_CRASHES_KEY to "0"))
    }

    /** The notice an earlier launch left, removed as it is read. */
    fun takeNotice(): Notice? {
        val raw = prefs.get(NOTICE_KEY)?.takeIf { it.isNotEmpty() } ?: return null
        prefs.set(mapOf(NOTICE_KEY to ""))
        return decodeNotice(raw)
    }

    /** The plugin a crash was blamed on, as far as the guard needs to know it. */
    data class Blamed(val id: String, val version: String, val name: String)

    private fun quickCrashes(): Int = prefs.get(QUICK_CRASHES_KEY)?.toIntOrNull() ?: 0

    companion object {
        /** The disabled set ProjectManager reads at start (same key, so a blamed plugin is simply off). */
        const val DISABLED_KEY = "plugins.disabled"
        const val CRASHED_KEY = "plugins.crashed"
        const val NOTICE_KEY = "plugins.crashNotice"
        const val QUICK_CRASHES_KEY = "startup.quickCrashes"

        /** A fatal crash this soon after the process started counts toward safe mode. */
        const val QUICK_CRASH_MS = 30_000L
        /** Unexplained quick crashes in a row before the next launch skips installed plugins. */
        const val SAFE_MODE_AFTER = 2
        /** How long a launch runs before its early-crash count clears. */
        const val STABLE_AFTER_MS = 60_000L

        // `id=version` pairs joined by '|': neither appears in a plugin id ([A-Za-z0-9._-]) or a version.
        internal fun encode(map: Map<String, String>): String =
            map.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" }

        internal fun decode(raw: String?): Map<String, String> =
            raw.orEmpty().split('|').mapNotNull { e ->
                val i = e.indexOf('=')
                if (i <= 0) null else e.substring(0, i) to e.substring(i + 1)
            }.toMap()

        // One line per field, so a title or body may hold anything but a newline (they never do).
        private fun encodeNotice(n: Notice): String =
            listOf(n.pluginId.orEmpty(), n.title, n.body).joinToString("\n") { it.replace('\n', ' ') }

        private fun decodeNotice(raw: String): Notice? {
            val parts = raw.split('\n')
            if (parts.size < 3) return null
            return Notice(parts[0].ifEmpty { null }, parts[1], parts[2])
        }
    }
}
