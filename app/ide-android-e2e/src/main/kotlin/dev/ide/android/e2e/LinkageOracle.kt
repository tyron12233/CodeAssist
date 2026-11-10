package dev.ide.android.e2e

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.fail

/**
 * Flags what a missing R8 keep rule looks like at runtime: a linkage/reflection error logged by any of the
 * app's processes (main, :build, :preview, :sandbox) while a test ran.
 *
 * Plenty of such errors are benign and happen on an unminified build too (optional-class probes,
 * desktop-only fallbacks, AndroidX feature detection). Those are listed as regexes in
 * `assets/benign-linkage.txt`, collected by running the suite against an R8-off build
 * (`-Pcodeassist.r8=false`). Everything else fails the test, with the stack head, so it can be read straight
 * off the test report.
 *
 * Every run also writes `linkage-all.txt` (every finding, benign or not) to the output dir, which is how the
 * benign list is regenerated.
 */
class LinkageOracle(private val shell: (String) -> String) {
    private var appUid: String? = null

    fun begin() {
        if (appUid == null) {
            appUid = Regex("""userId=(\d+)""").find(shell("dumpsys package ${E2eTest.APP}"))?.groupValues?.get(1)
        }
    }

    fun assertClean(outputDir: File) {
        val log = shell("logcat -d -v uid -v threadtime *:V")
        val findings = findings(log)
        if (findings.isEmpty()) return
        File(outputDir, "linkage-all.txt").appendText(findings.joinToString("\n\n", postfix = "\n\n"))
        val benign = benignPatterns()
        val real = findings.filter { f -> benign.none { it.containsMatchIn(f.lineSequence().first()) } }
        if (real.isNotEmpty()) {
            fail("The app logged ${real.size} linkage error(s) R8 may have caused:\n\n" + real.joinToString("\n\n"))
        }
    }

    /** Each finding = the exception line plus up to 8 frames, deduplicated on the exception line. */
    private fun findings(log: String): List<String> {
        val uid = appUid
        val names = if (uid == null) emptySet() else setOf(uid, "u0_a${uid.toInt() - 10_000}")
        val lines = log.lineSequence().filter { line ->
            // `-v uid` puts the uid (or its user name) as the third token; keep the app's lines only.
            names.isEmpty() || line.split(Regex("\\s+")).getOrNull(2) in names
        }.toList()
        val out = LinkedHashMap<String, String>()
        for ((i, line) in lines.withIndex()) {
            val msg = message(line)
            if (!LINKAGE.containsMatchIn(msg) && !msg.startsWith("FATAL EXCEPTION")) continue
            val frames = lines.drop(i + 1).asSequence().map(::message)
                .takeWhile { it.trimStart().startsWith("at ") || it.trimStart().startsWith("Caused by") || it.trimStart().startsWith("...") }
                .take(8).toList()
            val key = msg.replace(Regex("@[0-9a-f]+"), "@…")
            out.putIfAbsent(key, (listOf(msg) + frames).joinToString("\n"))
        }
        return out.values.toList()
    }

    /** The log message of a `-v uid -v threadtime` line: "date time uid pid tid L Tag: message". */
    private fun message(line: String): String =
        THREADTIME.find(line)?.groupValues?.get(1) ?: line

    private fun benignPatterns(): List<Regex> =
        InstrumentationRegistry.getInstrumentation().context.assets.open("benign-linkage.txt").bufferedReader()
            .readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map(::Regex)

    private companion object {
        val THREADTIME = Regex("""^\S+\s+\S+\s+\S+\s+\d+\s+\d+\s+[VDIWEFA]\s+[^:]*?:\s?(.*)$""")
        val LINKAGE = Regex(
            "(ClassNotFoundException|NoClassDefFoundError|NoSuchMethodError|NoSuchMethodException|NoSuchFieldError|" +
                "NoSuchFieldException|AbstractMethodError|IncompatibleClassChangeError|ExceptionInInitializerError|" +
                "VerifyError|ServiceConfigurationError|InstantiationException)",
        )
    }
}
