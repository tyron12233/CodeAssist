package dev.ide.core.plugins

/**
 * Which installed plugin a crash or a stalled main thread belongs to, read off the stack.
 *
 * A frame is a plugin's when its class was defined by that plugin's own classloader. Asking by name alone
 * would not do: every plugin loader parents the IDE's, so the IDE's classes (and the SDK's, and Kotlin's)
 * resolve through each of them too; only the defining loader tells whose code it is.
 *
 * The topmost plugin frame wins, across the throwable, its causes and its suppressed exceptions: that is the
 * plugin code that was running when things went wrong, even when the throw itself came from a library the
 * plugin called. Frames of packages no plugin can define (the platform, Kotlin, the IDE itself) are skipped
 * before any loader is asked, so the common case costs a string compare per frame.
 */
class PluginBlame(
    /** Per installed plugin id: whether that plugin's classloader defines the named class. */
    private val owners: Map<String, (className: String) -> Boolean>,
) {

    /** The plugin whose code is topmost in [t]'s stack (or a cause's), or null when no plugin frame is in it. */
    fun blame(t: Throwable): String? {
        if (owners.isEmpty()) return null
        val seen = HashSet<Throwable>()
        var depth = 0
        fun visit(x: Throwable?): String? {
            if (x == null || !seen.add(x) || depth++ > MAX_THROWABLES) return null
            blame(x.stackTrace.asList())?.let { return it }
            for (s in x.suppressed) visit(s)?.let { return it }
            return visit(x.cause)
        }
        return visit(t)
    }

    /** The plugin whose code is topmost in [frames], or null. */
    fun blame(frames: List<StackTraceElement>): String? {
        if (owners.isEmpty()) return null
        val checked = HashMap<String, String?>()
        for (frame in frames) {
            val name = frame.className
            if (FOREIGN_PREFIXES.any { name.startsWith(it) }) continue
            val owner = checked.getOrPut(name) {
                owners.entries.firstOrNull { (_, owns) -> runCatching { owns(name) }.getOrDefault(false) }?.key
            }
            if (owner != null) return owner
        }
        return null
    }

    companion object {
        private const val MAX_THROWABLES = 16

        /**
         * Packages a plugin cannot define classes in, because the IDE's loader (a plugin loader's parent) owns
         * them and parent-first delegation always answers from there.
         */
        private val FOREIGN_PREFIXES = listOf(
            "java.", "javax.", "jdk.", "sun.", "android.", "androidx.", "com.android.", "dalvik.", "libcore.",
            "kotlin.", "kotlinx.", "dev.ide.", "org.jetbrains.", "com.google.android.",
        )

        /** Blame by each plugin's real classloader: a class is the plugin's when that loader defined it. */
        fun ofLoaders(loaders: Map<String, ClassLoader>): PluginBlame = PluginBlame(
            loaders.mapValues { (_, loader) ->
                { name: String -> Class.forName(name, false, loader).classLoader === loader }
            },
        )
    }
}
