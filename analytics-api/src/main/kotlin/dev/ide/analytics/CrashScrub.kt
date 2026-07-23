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
        var anyOwn = false
        for (f in t.stackTrace) {
            if (out.size >= MAX_FRAMES) break
            if (isOwn(f)) {
                if (skipped > 0) { out.add("… ($skipped frames)"); skipped = 0 }
                out.add(frame(f))
                anyOwn = true
            } else {
                skipped++
            }
        }
        // A deep framework crash (Compose runtime, Android looper) can bury the first of OUR frames past the
        // cap — or entirely, with our code only on the cause chain — leaving an untriageable report (we saw a
        // large share of crashes with no own frame at all). Guarantee the deepest own frame anywhere in the
        // exception's own trace or its causes, so every crash pins to a call site we can act on.
        if (!anyOwn) deepestOwnFrame(t)?.let { out.add(it) }
        return out.joinToString("\n")
    }

    private fun isOwn(f: StackTraceElement) = ownPackagePrefixes.any { f.className.startsWith(it) }
    private fun frame(f: StackTraceElement) = "${f.className}.${f.methodName}:${f.lineNumber}"

    /** The first own frame found walking the exception and its cause chain (cycle-guarded); null if none. */
    private fun deepestOwnFrame(t: Throwable): String? {
        val seen = HashSet<Throwable>()
        var cur: Throwable? = t
        while (cur != null && seen.add(cur)) {
            cur.stackTrace.firstOrNull { isOwn(it) }?.let { return frame(it) }
            cur = cur.cause
        }
        return null
    }
}
