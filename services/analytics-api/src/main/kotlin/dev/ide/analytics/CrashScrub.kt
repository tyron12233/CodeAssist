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

    /**
     * Platform/library packages that are safe to name when a crash has NONE of our own frames anywhere (neither
     * the exception nor its cause chain). These hold no user data — the user's program runs on the interpreter
     * VM, never as real host JVM frames — so a `kotlin.collections.CollectionsKt.first` or
     * `androidx.compose.*` frame is a class+method+line of the framework, not PII. Without this, a crash buried
     * entirely in framework code reports an EMPTY `frames` and is untriageable (we saw a large `NoSuchElement`/
     * `ClassCast` cluster with nothing to act on). Bounded to a few top frames of the deepest cause.
     */
    private val platformPackagePrefixes: List<String> = listOf(
        "androidx.", "android.", "java.", "javax.", "kotlin.", "kotlinx.",
        "org.jetbrains.", "com.google.", "com.android.", "dalvik.", "sun.", "libcore.",
    )
    private const val MAX_FALLBACK_FRAMES = 8

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
        // exception's own trace or its causes, so every crash pins to a call site we can act on. If there is no
        // own frame ANYWHERE, fall back to the deepest cause's top platform frames so the crash site is still
        // visible instead of an empty report.
        if (!anyOwn) {
            val own = deepestOwnFrame(t)
            if (own != null) out.add(own) else out.addAll(platformFallbackFrames(t))
        }
        return out.joinToString("\n")
    }

    private fun isOwn(f: StackTraceElement) = ownPackagePrefixes.any { f.className.startsWith(it) }
    private fun isPlatform(f: StackTraceElement) = platformPackagePrefixes.any { f.className.startsWith(it) }
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

    /**
     * When no own frame exists anywhere, the top platform frames of the deepest cause (the actual origin) — so
     * a crash that lives entirely in framework/stdlib code still reports WHERE it threw. Privacy-safe: only
     * recognized platform packages, class+method+line, no messages. Empty if the trace has no platform frame.
     */
    private fun platformFallbackFrames(t: Throwable): List<String> {
        val chain = ArrayList<Throwable>()
        val seen = HashSet<Throwable>()
        var cur: Throwable? = t
        while (cur != null && seen.add(cur)) { chain.add(cur); cur = cur.cause }
        for (e in chain.asReversed()) { // deepest cause first
            val frames = e.stackTrace.asSequence().filter { isPlatform(it) }.take(MAX_FALLBACK_FRAMES)
                .map { frame(it) }.toList()
            if (frames.isNotEmpty()) return frames
        }
        return emptyList()
    }
}
