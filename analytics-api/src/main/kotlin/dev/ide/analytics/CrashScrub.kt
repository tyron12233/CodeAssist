package dev.ide.analytics

/**
 * Turns a throwable into a **safe** property set for analytics: the exception type chain and our own stack
 * frames only — NO exception messages, NO file paths. Pure and dependency-free so both the impl (the
 * `error_logged` log bridge) and the engine host (the fatal `app_crash` report) share one scrubber.
 *
 * The privacy rule: a raw stack trace can leak file paths, project names, and user code via messages, so we
 * keep types + our frames and drop everything else.
 */
object CrashScrub {

    /** Packages considered "ours" — only these frames are reported (others collapse to a count). */
    var ownPackagePrefixes: List<String> = listOf("dev.ide.")
    private const val MAX_FRAMES = 30

    /** `{exception: "Type <- CauseType <- …", frames: "Class.method:line\n…"}` — scrubbed. */
    fun scrub(t: Throwable): Map<String, String> = mapOf(
        "exception" to exceptionChain(t),
        "frames" to ownFrames(t),
    )

    /** Type chain only (no messages): `outer.Type <- cause.Type <- …`, capped and cycle-guarded. */
    fun exceptionChain(t: Throwable): String {
        val seen = HashSet<Throwable>()
        val chain = ArrayList<String>()
        var cur: Throwable? = t
        while (cur != null && seen.add(cur) && chain.size < 8) {
            chain.add(cur.javaClass.name)
            cur = cur.cause
        }
        return chain.joinToString(" <- ")
    }

    /** Our frames only, `Class.method:line` per line; runs of foreign frames collapse to `… (n frames)`. */
    fun ownFrames(t: Throwable): String {
        val out = ArrayList<String>()
        var skipped = 0
        for (f in t.stackTrace) {
            if (out.size >= MAX_FRAMES) break
            if (ownPackagePrefixes.any { f.className.startsWith(it) }) {
                if (skipped > 0) { out.add("… ($skipped frames)"); skipped = 0 }
                out.add("${f.className}.${f.methodName}:${f.lineNumber}")
            } else {
                skipped++
            }
        }
        return out.joinToString("\n")
    }
}
