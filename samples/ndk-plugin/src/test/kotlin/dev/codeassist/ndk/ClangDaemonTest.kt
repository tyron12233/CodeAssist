package dev.codeassist.ndk

import dev.ide.platform.log.Log
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import java.nio.file.attribute.PosixFilePermissions

/**
 * The client half of the daemon protocol, against a stand-in daemon that speaks the same framing.
 *
 * The real one is C over libclang and only runs on the device; what matters here is that the client frames
 * requests the way ndkclangd.c reads them, reads answers back the way it writes them, and that every way
 * the daemon can let it down ends in null (run clang directly) rather than a hang or a wrong answer.
 */
class ClangDaemonTest {

    @TempDir
    lateinit var dir: Path

    private fun python(): String? = listOf("/usr/bin/python3", "/usr/local/bin/python3", "/opt/homebrew/bin/python3")
        .firstOrNull { File(it).canExecute() }

    /** A fake daemon: echoes what it was sent, so the test can see the framing arrived intact. */
    private fun fake(behaviour: String = "echo"): Path {
        val py = python()
        assumeTrue(py != null, "needs python3 for the stand-in daemon")
        val script = dir.resolve("fake-daemon")
        script.writeText(
            """
            |#!$py
            |import sys
            |inp, out = sys.stdin.buffer, sys.stdout.buffer
            |mode = "$behaviour"
            |while True:
            |    header = inp.readline()
            |    if not header: break
            |    cmd, rid, line, col, n = header.decode().split()
            |    blobs = []
            |    for _ in range(int(n)):
            |        size = int(inp.readline())
            |        blobs.append(inp.read(size))
            |    if mode == "die": sys.exit(1)
            |    if mode == "hang":
            |        import time; time.sleep(60)
            |    if cmd == "complete" and blobs[1] == b"bad":
            |        body = b"clang could not parse the file"; status = b"error"
            |    else:
            |        body = (cmd + " " + line + ":" + col + "\n").encode() + b"\n".join(blobs); status = b"ok"
            |    out.write(rid.encode() + b" " + status + b" " + str(len(body)).encode() + b"\n" + body)
            |    out.flush()
            """.trimMargin()
        )
        script.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        return script
    }

    private fun daemon(exe: Path) = ClangDaemon(exe, dir, dir.resolve("tmp"), Log.logger("test"))

    @Test
    fun framesARequestAndReadsTheAnswer() {
        val d = daemon(fake())
        // Multi-line, non-ASCII text: the framing is by byte length, so neither may confuse it.
        val text = "int x;\n// é ünïcode\nint y;\n"
        val reply = d.request("complete", "/p/a.cpp", text, listOf("-std=c++17", "-x", "c++"), line = 3, column = 5)
        assertNotNull(reply)
        assertTrue(reply!!.ok)
        assertEquals("complete 3:5\n/p/a.cpp\n$text\n-std=c++17\n-x\nc++", reply.payload)

        // The same process answers the next request: the ids stay in step.
        assertEquals("check 0:0\n/p/a.cpp\nx", d.request("check", "/p/a.cpp", "x", emptyList())!!.payload)
        d.stop()
    }

    @Test
    fun anErrorAnswerIsNotAnOkOne() {
        val d = daemon(fake())
        val reply = d.request("complete", "/p/a.cpp", "bad", emptyList())!!
        assertTrue(!reply.ok)
        assertEquals("clang could not parse the file", reply.payload)
        d.stop()
    }

    @Test
    fun aDaemonThatDiesAnswersNullAndIsGivenUpOn() {
        val d = daemon(fake("die"))
        repeat(ClangDaemon.MAX_DEATHS) { assertNull(d.request("check", "/p/a.cpp", "x", emptyList())) }
        // Left off now, without even trying: nothing is started for this one.
        assertNull(d.request("check", "/p/a.cpp", "x", emptyList()))
    }

    @Test
    fun aHungDaemonTimesOut() {
        val d = daemon(fake("hang"))
        val started = System.nanoTime()
        assertNull(d.request("check", "/p/a.cpp", "x", emptyList(), timeoutSeconds = 1))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000)
    }

    @Test
    fun aRequestCancelledBeforeItsTurnIsNeverSent() {
        val d = daemon(fake())
        val cancellation = ToolCancellation().apply { cancel() }
        assertNull(d.request("check", "/p/a.cpp", "x", emptyList(), cancellation = cancellation))
        // ...and that did not count against the daemon.
        assertNotNull(d.request("check", "/p/a.cpp", "x", emptyList()))
        d.stop()
    }

    @Test
    fun noExecutableMeansNoDaemon() {
        assertNull(daemon(dir.resolve("missing")).request("check", "/p/a.cpp", "x", emptyList()))
    }
}
