package dev.ide.agent.mcp

import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives [FtpServer] with a tiny raw-socket FTP client (same wire format curl/`ftplib` speak), covering the
 * passive-mode asset round trip: STOR → SIZE → RETR → LIST → NLST → MDTM → MKD/CWD/DELE/RMD, plus the path
 * traversal guard. Each test gets its own temp root and ephemeral port.
 */
class FtpServerTest {

    private val tempDir: Path = Files.createTempDirectory("ftp-server-test")
    private val server = FtpServer(tempDir, port = 0).start()
    private val client = RawFtpClient(server.boundPort)

    @Test
    fun roundTrip() {
        assertEquals(220, client.greetingCode)
        assertEquals(331, client.cmd("USER anonymous"))
        assertEquals(230, client.cmd("PASS anonymous@"))
        assertEquals(200, client.cmd("TYPE I"))
        assertEquals(200, client.cmd("STRU F"))
        assertEquals(200, client.cmd("MODE S"))

        // STOR (binary passthrough)
        val payload = "hello world\n".toByteArray(Charsets.UTF_8)
        client.dataCmd("STOR hello.txt", payload)
        assertEquals(226, client.lastCode)
        assertTrue(Files.readAllBytes(tempDir.resolve("hello.txt")).contentEquals(payload))

        // SIZE
        assertTrue(client.replyText("SIZE hello.txt").startsWith("213 ${payload.size}"))

        // RETR round-trips the exact bytes
        assertEquals(payload.toString(Charsets.UTF_8), client.dataText("RETR hello.txt"))
        assertEquals(226, client.lastCode)

        // LIST / NLST advertise the file
        assertTrue(client.dataText("LIST").contains("hello.txt"))
        assertTrue(client.dataText("NLST").contains("hello.txt"))

        // MDTM
        assertTrue(client.replyText("MDTM hello.txt").startsWith("213 "))

        // Subdirectory navigation + nested upload
        assertEquals(257, client.cmd("MKD sub"))
        assertEquals(250, client.cmd("CWD sub"))
        assertTrue(client.replyText("PWD").startsWith("257 \"/sub\""))
        client.dataCmd("STOR other.txt", "x".toByteArray())
        assertEquals(226, client.lastCode)
        assertEquals(250, client.cmd("DELE other.txt"))
        assertEquals(250, client.cmd("CDUP"))
        assertEquals(250, client.cmd("RMD sub"))
        assertEquals(250, client.cmd("DELE hello.txt"))
        assertTrue(!Files.exists(tempDir.resolve("hello.txt")))

        // QUIT
        assertEquals(221, client.cmd("QUIT"))
    }

    @Test
    fun rejectsPathTraversal() {
        client.greetingCode
        client.cmd("USER anonymous")
        client.cmd("PASS x")
        assertEquals(550, client.cmd("STOR ../escape.txt"))
        assertTrue(!Files.exists(tempDir.parent.resolve("escape.txt")))
    }

    /** A tiny FTP control client (enough for USER/PASS/PASV/STOR/RETR/LIST/... over one socket). */
    private class RawFtpClient(port: Int) : AutoCloseable {
        private val socket = Socket("127.0.0.1", port).apply { soTimeout = 10_000 }
        private val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.US_ASCII))

        val greetingCode: Int = readReply().take(3).toInt()

        private var lastReply: String = ""

        val lastCode: Int get() = lastReply.take(3).toInt()

        /** Sends a command and returns the numeric reply code (last line for multi-line replies). */
        fun cmd(line: String): Int {
            writer.write("$line\r\n")
            writer.flush()
            lastReply = readReply()
            return lastCode
        }

        /** Sends a command and returns the full reply text (for content assertions). */
        fun replyText(line: String): String {
            cmd(line)
            return lastReply
        }

        /** Sends a PASV data command ([verbArg], e.g. `STOR x.txt`) and uploads [payload], returning the
         *  completion code. */
        fun dataCmd(verbArg: String, payload: ByteArray): Int {
            val port = dataPort(cmdPASV())
            writer.write("$verbArg\r\n")
            writer.flush()
            val pre = readReply()
            check(pre.startsWith("150")) { "expected 150 for $verbArg, got: $pre" }
            Socket("127.0.0.1", port).apply { soTimeout = 10_000 }.use { it.getOutputStream().write(payload) }
            lastReply = readReply()
            return lastCode
        }

        /** Downloads the data for [verbArg] (e.g. `RETR x.txt`) and returns the raw bytes. */
        fun dataText(verbArg: String): String {
            val port = dataPort(cmdPASV())
            writer.write("$verbArg\r\n")
            writer.flush()
            val pre = readReply()
            check(pre.startsWith("150")) { "expected 150 for $verbArg, got: $pre" }
            val bytes = Socket("127.0.0.1", port).apply { soTimeout = 10_000 }.use { it.getInputStream().readBytes() }
            lastReply = readReply()
            return bytes.toString(Charsets.UTF_8)
        }

        private fun cmdPASV(): String {
            writer.write("PASV\r\n")
            writer.flush()
            val reply = readReply()
            check(reply.startsWith("227")) { "expected 227, got: $reply" }
            return reply
        }

        private fun dataPort(pasvReply: String): Int {
            val groups = Regex("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)")
                .find(pasvReply)?.groupValues?.drop(1)?.map { it.toInt() }
                ?: throw IOException("no passive port in: $pasvReply")
            return groups[4] * 256 + groups[5]
        }

        private fun readReply(): String {
            val sb = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: throw IOException("connection closed")
                sb.append(line).append('\n')
                // A multi-line reply continues while the 4th char is '-'; it ends on a plain "NNN ...".
                if (line.length < 4 || line[3] != '-') break
            }
            return sb.toString().trim()
        }

        override fun close() = socket.close()
    }
}
