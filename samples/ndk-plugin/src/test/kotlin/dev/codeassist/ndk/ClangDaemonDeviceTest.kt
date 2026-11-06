package dev.codeassist.ndk

import dev.ide.platform.log.Log
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.writeText

/**
 * The REAL daemon, on a device. Skipped everywhere else: it needs `libndkclangd.so` and the toolchain it
 * runs against, which only exist on arm64 Android.
 *
 * Run on ART with `-Dndk.lib=<dir holding libndkclangd.so, libclang.so, ...>` and
 * `-Dndk.toolchain=<unpacked assets/toolchain>` (see samples/ndk-plugin/run-tests-on-device.sh). This is
 * what covers the half the JVM cannot: the C side of the protocol, libclang's answers going through the
 * Kotlin parsers on ART's regex engine, and a daemon that dies mid-session falling back and coming back.
 */
class ClangDaemonDeviceTest {

    private lateinit var lib: Path
    private lateinit var toolchain: Path
    private lateinit var work: Path
    private lateinit var daemon: ClangDaemon

    @BeforeEach
    fun setUp() {
        val libDir = System.getProperty("ndk.lib")
        val tc = System.getProperty("ndk.toolchain")
        assumeTrue(libDir != null && tc != null && File(libDir, "libndkclangd.so").isFile, "device only")
        lib = Paths.get(libDir!!)
        toolchain = Paths.get(tc!!)
        work = Files.createTempDirectory(Paths.get(System.getProperty("java.io.tmpdir")), "daemon-test")
        daemon = ClangDaemon(lib.resolve("libndkclangd.so"), lib, work.resolve("tmp"), Log.logger("device-test"))
    }

    @AfterEach
    fun tearDown() {
        if (::daemon.isInitialized) daemon.stop()
        if (::work.isInitialized) work.toFile().deleteRecursively()
    }

    private fun args(cpp: Boolean = true) = buildList {
        add("--target=${NdkToolchain.TARGET_TRIPLE}")
        add("-resource-dir"); add(toolchain.resolve("lib-clang").resolve(NdkToolchain.LLVM_MAJOR).toString())
        add("--sysroot"); add(toolchain.resolve("sysroot").toString())
        if (cpp) { add("-std=c++17"); add("-fpch-instantiate-templates"); add("-x"); add("c++") }
        else { add("-std=c11"); add("-x"); add("c") }
    }

    private val source = """
        |#include <vector>
        |#include <string>
        |#include "local.h"
        |
        |int f() {
        |    std::vector<std::string> v;
        |    Point p{1, 2};
        |    v.
        |    return p.x + undeclared;
        |}
        |""".trimMargin()

    private fun file(): Path {
        work.resolve("local.h").writeText("struct Point { int x; int y; };\n")
        return work.resolve("a.cpp").also { it.writeText(source) }
    }

    @Test
    fun checkReportsTheErrorsOnTheirLines() {
        val path = file()
        val reply = daemon.request("check", path.toString(), source, args())
        assertNotNull(reply)
        assertTrue(reply!!.ok, reply.payload)
        val diagnostics = ClangDiagnostics.parse(reply.payload, source, bufferName = path.toString())
        val lines = LineOffsets(source)
        val atLine = diagnostics.map { lines.lineColOf(it.range.start).first }.toSet()
        // `v.` on line 8 is an incomplete member access, `undeclared` on line 9 an unknown name. The quote
        // include resolved, or line 7 would be an error too.
        assertTrue(9 in atLine, "expected an error on line 9 in $diagnostics")
        assertTrue(7 !in atLine, "local.h did not resolve: $diagnostics")
    }

    @Test
    fun completionOffersTheRealMembersWithKinds() {
        val path = file()
        val reply = daemon.request("complete", path.toString(), source, args(), line = 8, column = 7)!!
        assertTrue(reply.ok, reply.payload)
        val candidates = ClangCompletions.parse(reply.payload).associateBy { it.name }
        val pushBack = candidates["push_back"]
        assertNotNull(pushBack, "no push_back in ${candidates.keys.take(20)}")
        assertEquals("CXXMethod", pushBack!!.kind)
        assertEquals(dev.ide.lang.completion.CompletionItemKind.METHOD, pushBack.toItem(cpp = true).kind)
        assertTrue("size" in candidates && "emplace_back" in candidates)
    }

    @Test
    fun semanticTokensClassifyWhatClangResolved() {
        val text = """
            |#include <vector>
            |#define LIMIT 4
            |struct Point { int x; };
            |int total = 0;
            |int sum(const std::vector<Point>& points, int scale) {
            |    int acc = LIMIT;
            |    for (const Point& p : points) acc += p.x * scale;
            |    return acc + total;
            |}
            |""".trimMargin()
        val path = work.resolve("sem.cpp").also { it.writeText(text) }
        assertNotNull(daemon.request("check", path.toString(), text, args()))
        val reply = daemon.request("tokens", path.toString(), text, args())!!
        assertTrue(reply.ok, reply.payload)
        val kinds = ClangSemanticTokens.parse(reply.payload, text)
            .groupBy({ text.substring(it.range.start, it.range.end) }, { it.kind.id })
        assertEquals(setOf(ClangSemanticTokens.MACRO_KIND), kinds["LIMIT"]?.toSet())
        assertEquals(setOf("class"), kinds["Point"]?.toSet())
        assertEquals(setOf("field"), kinds["x"]?.toSet())
        assertEquals(setOf("field"), kinds["total"]?.toSet(), "a global reads as storage outside any call")
        assertEquals(setOf("function"), kinds["sum"]?.toSet())
        assertEquals(setOf("parameter"), kinds["points"]?.toSet())
        assertEquals(setOf("parameter"), kinds["scale"]?.toSet())
        assertEquals(setOf("localVariable"), kinds["acc"]?.toSet())
        assertEquals(setOf("localVariable"), kinds["p"]?.toSet())
        assertEquals(setOf("namespace"), kinds["std"]?.toSet())
    }

    @Test
    fun cCompletionIncludesMacros() {
        val c = "#include <stdio.h>\n#define MY_TAG \"x\"\nint main(void) {\n    MY_\n    return 0;\n}\n"
        val path = work.resolve("m.c").also { it.writeText(c) }
        val reply = daemon.request("complete", path.toString(), c, args(cpp = false), line = 4, column = 5)!!
        val tag = ClangCompletions.parse(reply.payload).firstOrNull { it.name == "MY_TAG" }
        assertNotNull(tag, "no MY_TAG")
        assertEquals("macrodefinition", tag!!.kind)
    }

    @Test
    fun theSecondRequestIsFastBecauseTheUnitStaysLoaded() {
        val path = file()
        assertNotNull(daemon.request("check", path.toString(), source, args()))
        val started = System.nanoTime()
        val reply = daemon.request("complete", path.toString(), source, args(), line = 8, column = 7)!!
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(reply.ok)
        println("DEVICE warm completion: $ms ms")
        assertTrue(ms < 2_000, "a warm completion took $ms ms")
    }

    /** Kill every daemon this test process started, the way the low-memory killer or a crash would. */
    private fun killDaemons() {
        ProcessBuilder("pkill", "-9", "-f", "libndkclangd.so").start().waitFor()
    }

    @Test
    fun aDaemonKilledBetweenRequestsIsRestartedBeforeTheNextOne() {
        val path = file()
        assertNotNull(daemon.request("check", path.toString(), source, args()))
        killDaemons()
        Thread.sleep(200)
        // Found dead before anything was sent, so a fresh one answers: no fallback needed at all.
        val reply = daemon.request("check", path.toString(), source, args())
        assertNotNull(reply, "the daemon did not come back")
        assertTrue(reply!!.ok)
    }

    @Test
    fun aDaemonKilledDuringARequestFallsBackThenComesBack() {
        val path = file()
        // The first parse of a file including <vector> and <string> builds its preamble: hundreds of
        // milliseconds, which is the window to kill it in.
        val killer = Thread { Thread.sleep(80); killDaemons() }.apply { start() }
        val during = daemon.request("check", path.toString(), source, args())
        killer.join()
        // The request it died under answers null: the caller runs clang directly for that one.
        assertNull(during, "a request whose daemon died under it must fall back")
        val reply = daemon.request("check", path.toString(), source, args())
        assertNotNull(reply, "the daemon did not come back")
        assertTrue(reply!!.ok)
    }

    @Test
    fun changingTheFlagsReparsesFromScratch() {
        val path = file()
        val withAuto = "int f() { auto x = 1; return x; }\n"
        path.writeText(withAuto)
        val cpp17 = daemon.request("check", path.toString(), withAuto, args())!!
        assertTrue(ClangDiagnostics.parse(cpp17.payload, withAuto, path.toString()).isEmpty(), cpp17.payload)
        // As C, `auto x = 1` is an error (C11 has no type deduction): the old unit must not be reused.
        val asC = daemon.request("check", path.toString(), withAuto, args(cpp = false))!!
        assertTrue(ClangDiagnostics.parse(asC.payload, withAuto, path.toString()).isNotEmpty(), asC.payload)
    }
}
