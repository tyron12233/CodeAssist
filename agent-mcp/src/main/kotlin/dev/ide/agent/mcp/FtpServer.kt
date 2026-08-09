package dev.ide.agent.mcp

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A minimal embedded FTP server used as the CodeAssist asset inbox: anonymous, bound to 127.0.0.1 only,
 * rooted at [root] (typically `<project>/assets`). Files uploaded with `STOR` land directly in [root], so
 * a client can drop screenshots, APKs, or any binary and they appear in the workspace for the agent to
 * consume. Serves the passive-mode subset a stock client (curl, `ftplib`, the OS file manager) needs:
 * USER/PASS (anonymous), TYPE, PASV/EPSV, STOR, RETR, LIST/NLST, CWD/PWD, MKD/RMD/DELE, SIZE, MDTM, ABOR.
 *
 * Follows the same zero-dependency style as [HttpStreamableServerTransportProvider]'s socket server: one
 * daemon accept thread, one daemon thread per control connection, and a short-lived passive data socket
 * bound to 127.0.0.1 for each transfer. `TYPE A` is accepted but transfers raw bytes like `TYPE I` (the
 * use-case is binary assets, where ASCII line-ending rewriting would corrupt the payload).
 */
class FtpServer(
    root: Path,
    private val port: Int = CodeAssistMcpServer.DEFAULT_FTP_PORT,
) : AutoCloseable {
    private val root: Path = root.toAbsolutePath().normalize()
    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)

    /** The port the listener is bound to, or -1 before [start]. */
    val boundPort: Int get() = serverSocket?.localPort ?: -1

    /** Starts the control listener on 127.0.0.1:[port] (0 picks an ephemeral port) and returns `this`. */
    fun start(): FtpServer {
        synchronized(lock) {
            check(serverSocket == null) { "FTP server already bound to port $boundPort" }
            Files.createDirectories(root)
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress("127.0.0.1", port))
            serverSocket = server
            running.set(true)
            Thread(::acceptLoop, "ftp-accept").apply { isDaemon = true }.start()
        }
        return this
    }

    override fun close() {
        synchronized(lock) {
            running.set(false)
            serverSocket?.close()
            serverSocket = null
        }
    }

    private fun acceptLoop() {
        while (running.get()) {
            val server = serverSocket ?: return
            if (server.isClosed) return
            val client = try {
                server.accept()
            } catch (e: IOException) {
                return
            }
            Thread({ handle(client) }, "ftp-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: Socket) {
        client.use {
            try {
                Session(client).run()
            } catch (e: IOException) {
                // client hung up; nothing to send
            } catch (e: Exception) {
                runCatching {
                    client.getOutputStream().write(
                        "500 Internal error: ${e.message}\r\n".toByteArray(StandardCharsets.US_ASCII),
                    )
                    client.getOutputStream().flush()
                }
            }
        }
    }

    /** Per-connection control session: owns the cwd, transfer type, and the current passive data socket. */
    private inner class Session(private val client: Socket) {
        private val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
        private val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), StandardCharsets.US_ASCII))

        /** POSIX-style path relative to [root] ("" is the root). */
        private var cwd = ""
        private var ascii = false
        private var passive: ServerSocket? = null

        fun run() {
            reply(220, "CodeAssist FTP asset server ready.")
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val sp = line.indexOf(' ')
                val verb = (if (sp > 0) line.substring(0, sp) else line).uppercase(Locale.ROOT)
                val arg = if (sp > 0) line.substring(sp + 1).trim() else ""
                if (!dispatch(verb, arg)) break
            }
        }

        private fun dispatch(verb: String, arg: String): Boolean = when (verb) {
            "USER" -> { reply(331, "User name okay, need password."); true }
            "PASS" -> { reply(230, "Logged in."); true }
            "QUIT" -> { reply(221, "Goodbye."); false }
            "NOOP" -> { reply(200, "NOOP okay."); true }
            "SYST" -> { reply(215, "UNIX Type: L8"); true }
            "TYPE" -> {
                ascii = arg.equals("A", ignoreCase = true)
                when {
                    ascii || arg.equals("I", ignoreCase = true) || arg.equals("L 8", ignoreCase = true) ->
                        reply(200, "Type set.")
                    else -> reply(504, "Type not supported.")
                }
                true
            }
            "STRU" -> { reply(200, "Structure okay."); true }
            "MODE" -> { reply(200, "Mode set."); true }
            "PORT" -> { reply(502, "PORT not supported; use PASV."); true }
            "PASV" -> pasv()
            "EPSV" -> epsv()
            "CWD" -> cwd(arg)
            "CDUP" -> cwd("..")
            "PWD" -> { reply(257, "\"/$cwd\" is the current directory."); true }
            "MKD" -> mkd(arg)
            "RMD" -> rmd(arg)
            "DELE" -> dele(arg)
            "SIZE" -> size(arg)
            "MDTM" -> mdtm(arg)
            "LIST" -> list(arg, detailed = true)
            "NLST" -> list(arg, detailed = false)
            "RETR" -> retr(arg)
            "STOR" -> stor(arg)
            "ABOR" -> { passive?.close(); passive = null; reply(225, "No transfer to abort."); true }
            "FEAT" -> { feat(); true }
            else -> { reply(502, "Command not implemented."); true }
        }

        // --- directory navigation ---

        private fun cwd(arg: String): Boolean {
            val target = resolve(arg) ?: run { reply(550, "No such directory."); return true }
            if (!Files.isDirectory(target)) {
                reply(550, "Not a directory.")
                return true
            }
            cwd = relativePath(target)
            reply(250, "Directory changed.")
            return true
        }

        private fun mkd(arg: String): Boolean {
            val target = resolve(arg) ?: run { reply(550, "Invalid path."); return true }
            return try {
                Files.createDirectories(target)
                reply(257, "\"$arg\" created.")
                true
            } catch (e: IOException) {
                reply(550, "Could not create directory: ${e.message}")
                true
            }
        }

        private fun rmd(arg: String): Boolean {
            val target = resolve(arg) ?: run { reply(550, "Invalid path."); return true }
            return try {
                Files.deleteIfExists(target)
                reply(250, "Directory removed.")
                true
            } catch (e: IOException) {
                reply(550, "Could not remove directory: ${e.message}")
                true
            }
        }

        private fun dele(arg: String): Boolean {
            val target = resolve(arg) ?: run { reply(550, "Invalid path."); return true }
            return try {
                Files.deleteIfExists(target)
                reply(250, "File deleted.")
                true
            } catch (e: IOException) {
                reply(550, "Could not delete file: ${e.message}")
                true
            }
        }

        private fun size(arg: String): Boolean {
            val target = resolve(arg)
            if (target == null || !Files.isRegularFile(target)) {
                reply(550, "Not a file.")
                return true
            }
            reply(213, Files.size(target).toString())
            return true
        }

        private fun mdtm(arg: String): Boolean {
            val target = resolve(arg)
            if (target == null || !Files.isRegularFile(target)) {
                reply(550, "Not a file.")
                return true
            }
            val stamp = Files.getLastModifiedTime(target).toInstant().atZone(ZoneOffset.UTC)
            reply(213, stamp.format(MDTM_FORMAT))
            return true
        }

        // --- transfers ---

        private fun list(arg: String, detailed: Boolean): Boolean {
            val items = when {
                arg.isBlank() -> childrenOf(resolve(cwd)!!)
                else -> {
                    val t = resolve(arg)
                    when {
                        t == null -> null
                        Files.isDirectory(t) -> childrenOf(t)
                        Files.exists(t) -> listOf(t)
                        else -> null
                    }
                }
            } ?: run { reply(550, "No such file or directory."); return true }
            val conn = dataConnection() ?: return true
            reply(150, "Opening data connection.")
            conn.use {
                val out = it.getOutputStream()
                for (f in items) {
                    val line = if (detailed) entryLine(f) else f.fileName.toString()
                    out.write(line.toByteArray(StandardCharsets.UTF_8))
                    out.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                }
                out.flush()
            }
            closeData()
            reply(226, "Transfer complete.")
            return true
        }

        private fun retr(arg: String): Boolean {
            val target = resolve(arg)
            if (target == null || !Files.isRegularFile(target)) {
                reply(550, "No such file.")
                return true
            }
            val conn = dataConnection() ?: return true
            reply(150, "Opening data connection.")
            conn.use {
                val out = it.getOutputStream()
                Files.newInputStream(target, StandardOpenOption.READ).use { input -> input.copyTo(out) }
                out.flush()
            }
            closeData()
            reply(226, "Transfer complete.")
            return true
        }

        private fun stor(arg: String): Boolean {
            val target = resolve(arg) ?: run { reply(550, "Invalid path."); return true }
            val conn = dataConnection() ?: return true
            reply(150, "Opening data connection.")
            return try {
                Files.createDirectories(target.parent)
                conn.use {
                    Files.newOutputStream(
                        target,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
                    ).use { out -> it.getInputStream().copyTo(out) }
                }
                closeData()
                reply(226, "Transfer complete.")
                true
            } catch (e: Exception) {
                closeData()
                reply(451, "Requested action aborted: ${e.message}")
                true
            }
        }

        private fun pasv(): Boolean {
            val s = passiveSocket()
            passive?.close()
            passive = s
            val p = s.localPort
            reply(227, "Entering Passive Mode (127,0,0,1,${p ushr 8},${p and 0xFF})")
            return true
        }

        private fun epsv(): Boolean {
            val s = passiveSocket()
            passive?.close()
            passive = s
            reply(229, "Entering Extended Passive Mode (|||${s.localPort}|)")
            return true
        }

        private fun passiveSocket(): ServerSocket {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress("127.0.0.1", 0))
            return s
        }

        /** Accepts the data connection for the current passive socket (15s window), or replies 425. */
        private fun dataConnection(): Socket? {
            val p = passive ?: run { reply(425, "Use PASV first."); return null }
            p.soTimeout = DATA_ACCEPT_TIMEOUT_MS
            return try {
                p.accept()
            } catch (e: IOException) {
                reply(425, "Can't open data connection.")
                null
            }
        }

        private fun closeData() {
            passive?.close()
            passive = null
        }

        // --- FEAT / helpers ---

        private fun feat() {
            writer.write("211-Features supported\r\n")
            writer.write(" UTF8\r\n")
            writer.write(" SIZE\r\n")
            writer.write(" MDTM\r\n")
            writer.write(" PASV\r\n")
            writer.write(" EPSV\r\n")
            writer.write("211 End\r\n")
            writer.flush()
        }

        /** Resolves an FTP path (relative to [cwd], or from the root when it starts with "/") to a real
         *  [Path] inside [root]; null when it would escape the root (e.g. `..`). */
        private fun resolve(arg: String): Path? {
            val cleaned = arg.replace('\\', '/')
            if (cleaned.isEmpty()) return resolve(cwd)
            val base = if (cleaned.startsWith("/")) root else root.resolve(cwd)
            val target = base.resolve(cleaned.trimStart('/')).normalize()
            return target.takeIf { it.startsWith(root) }
        }

        private fun relativePath(p: Path): String {
            val rel = root.relativize(p).toString()
            return if (rel.isEmpty()) "" else rel.replace('\\', '/')
        }

        private fun childrenOf(dir: Path): List<Path>? =
            if (Files.isDirectory(dir)) Files.list(dir).use { stream -> stream.sorted().toList() } else null

        private fun entryLine(f: Path): String {
            val isDir = Files.isDirectory(f)
            val size = if (isDir) 0L else runCatching { Files.size(f) }.getOrDefault(0L)
            val mtime = runCatching { Files.getLastModifiedTime(f).toInstant().atZone(ZoneOffset.UTC) }
                .getOrDefault(LIST_EPOCH)
            return String.format(
                Locale.ROOT,
                "%s %3s %3s %10d %s %s",
                if (isDir) "drwxr-xr-x" else "-rw-r--r--",
                "ftp", "ftp", size, mtime.format(LIST_DATE), f.fileName.toString(),
            )
        }

        private fun reply(code: Int, text: String) {
            writer.write("$code $text\r\n")
            writer.flush()
        }
    }

    private companion object {
        const val DATA_ACCEPT_TIMEOUT_MS = 15_000
        val LIST_DATE = DateTimeFormatter.ofPattern("MMM dd HH:mm", Locale.ROOT)
        val MDTM_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT)
        val LIST_EPOCH = ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
    }
}
