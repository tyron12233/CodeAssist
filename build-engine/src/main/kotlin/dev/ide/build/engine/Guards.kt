package dev.ide.build.engine

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.io.FileWriter
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.URL
import java.net.URLConnection
import java.nio.charset.Charset
import java.nio.file.OpenOption
import java.nio.file.Path
import java.util.stream.Stream

/** The kinds of sensitive operation the run-time guard mediates (the categories the permission UI prompts for). */
enum class GuardCategory { NETWORK, FILE_READ, FILE_WRITE, REFLECTION, EXEC }

/**
 * Decides whether a guarded operation may proceed. Implemented by the host (ide-core), which blocks the
 * running program's thread and prompts the user. Called from instrumented user/library code via [Guards];
 * runs are sequential, so a single process-wide [Guards.broker] is sufficient.
 */
interface PermissionBroker {
    /** Blocking: may the running program perform [category] (with human-readable [detail])? */
    fun check(category: GuardCategory, detail: String): Boolean
}

/**
 * The run-time targets that [SandboxGuard] rewrites sensitive calls to: each checks the [broker] for its
 * [GuardCategory] and, if allowed, performs the original operation; if denied, throws [SecurityException].
 * With no [broker] set (code not running under a guarded run) every trampoline is a transparent pass-through.
 *
 * This guards a curated set of common entry points; it is not a hardened sandbox, and native code or
 * an uninstrumented path can bypass it.
 */
object Guards {
    @Volatile @JvmStatic var broker: PermissionBroker? = null

    private fun guard(category: GuardCategory, detail: String) {
        val b = broker ?: return
        if (!b.check(category, detail)) throw SecurityException("Blocked ${category.name.lowercase()}: $detail")
    }

    // ---- network ----
    @JvmStatic fun openConnection(url: URL): URLConnection { guard(GuardCategory.NETWORK, url.toString()); return url.openConnection() }
    @JvmStatic fun openConnection(url: URL, proxy: Proxy): URLConnection { guard(GuardCategory.NETWORK, url.toString()); return url.openConnection(proxy) }
    @JvmStatic fun openStream(url: URL): InputStream { guard(GuardCategory.NETWORK, url.toString()); return url.openStream() }
    @JvmStatic fun socketConnect(s: Socket, ep: SocketAddress) { guard(GuardCategory.NETWORK, ep.toString()); s.connect(ep) }
    @JvmStatic fun socketConnect(s: Socket, ep: SocketAddress, timeout: Int) { guard(GuardCategory.NETWORK, ep.toString()); s.connect(ep, timeout) }
    @JvmStatic fun getByName(host: String?): InetAddress { guard(GuardCategory.NETWORK, "dns:$host"); return InetAddress.getByName(host) }
    @JvmStatic fun getAllByName(host: String?): Array<InetAddress> { guard(GuardCategory.NETWORK, "dns:$host"); return InetAddress.getAllByName(host) }
    @JvmStatic fun newSocket(host: String?, port: Int): Socket { guard(GuardCategory.NETWORK, "$host:$port"); return Socket(host, port) }
    @JvmStatic fun newSocket(addr: InetAddress?, port: Int): Socket { guard(GuardCategory.NETWORK, "$addr:$port"); return Socket(addr, port) }
    @JvmStatic fun newSocket(host: String?, port: Int, localAddr: InetAddress?, localPort: Int): Socket { guard(GuardCategory.NETWORK, "$host:$port"); return Socket(host, port, localAddr, localPort) }
    @JvmStatic fun newSocket(addr: InetAddress?, port: Int, localAddr: InetAddress?, localPort: Int): Socket { guard(GuardCategory.NETWORK, "$addr:$port"); return Socket(addr, port, localAddr, localPort) }

    // ---- file: read ----
    @JvmStatic fun newFileInputStream(f: File): FileInputStream { guard(GuardCategory.FILE_READ, f.path); return FileInputStream(f) }
    @JvmStatic fun newFileInputStream(name: String): FileInputStream { guard(GuardCategory.FILE_READ, name); return FileInputStream(name) }
    @JvmStatic fun newFileReader(f: File): FileReader { guard(GuardCategory.FILE_READ, f.path); return FileReader(f) }
    @JvmStatic fun newFileReader(name: String): FileReader { guard(GuardCategory.FILE_READ, name); return FileReader(name) }
    @JvmStatic fun filesNewInputStream(p: Path, opts: Array<out OpenOption>): InputStream { guard(GuardCategory.FILE_READ, p.toString()); return java.nio.file.Files.newInputStream(p, *opts) }
    @JvmStatic fun filesNewBufferedReader(p: Path): java.io.BufferedReader { guard(GuardCategory.FILE_READ, p.toString()); return java.nio.file.Files.newBufferedReader(p) }
    @JvmStatic fun filesNewBufferedReader(p: Path, cs: Charset): java.io.BufferedReader { guard(GuardCategory.FILE_READ, p.toString()); return java.nio.file.Files.newBufferedReader(p, cs) }
    @JvmStatic fun filesReadAllBytes(p: Path): ByteArray { guard(GuardCategory.FILE_READ, p.toString()); return java.nio.file.Files.readAllBytes(p) }
    @JvmStatic fun filesReadAllLines(p: Path): MutableList<String> { guard(GuardCategory.FILE_READ, p.toString()); return java.nio.file.Files.readAllLines(p) }
    @JvmStatic fun filesReadAllLines(p: Path, cs: Charset): MutableList<String> { guard(GuardCategory.FILE_READ, p.toString()); return java.nio.file.Files.readAllLines(p, cs) }
    @JvmStatic fun filesReadString(p: Path): String { guard(GuardCategory.FILE_READ, p.toString()); return java.nio.file.Files.readString(p) }
    @JvmStatic fun filesReadString(p: Path, cs: Charset): String { guard(GuardCategory.FILE_READ, p.toString()); return java.nio.file.Files.readString(p, cs) }
    @JvmStatic fun filesLines(p: Path): Stream<String> { guard(GuardCategory.FILE_READ, p.toString()); return java.nio.file.Files.lines(p) }
    @JvmStatic fun filesLines(p: Path, cs: Charset): Stream<String> { guard(GuardCategory.FILE_READ, p.toString()); return java.nio.file.Files.lines(p, cs) }

    // ---- file: write ----
    @JvmStatic fun newFileOutputStream(f: File): FileOutputStream { guard(GuardCategory.FILE_WRITE, f.path); return FileOutputStream(f) }
    @JvmStatic fun newFileOutputStream(f: File, append: Boolean): FileOutputStream { guard(GuardCategory.FILE_WRITE, f.path); return FileOutputStream(f, append) }
    @JvmStatic fun newFileOutputStream(name: String): FileOutputStream { guard(GuardCategory.FILE_WRITE, name); return FileOutputStream(name) }
    @JvmStatic fun newFileOutputStream(name: String, append: Boolean): FileOutputStream { guard(GuardCategory.FILE_WRITE, name); return FileOutputStream(name, append) }
    @JvmStatic fun newFileWriter(f: File): FileWriter { guard(GuardCategory.FILE_WRITE, f.path); return FileWriter(f) }
    @JvmStatic fun newFileWriter(f: File, append: Boolean): FileWriter { guard(GuardCategory.FILE_WRITE, f.path); return FileWriter(f, append) }
    @JvmStatic fun newFileWriter(name: String): FileWriter { guard(GuardCategory.FILE_WRITE, name); return FileWriter(name) }
    @JvmStatic fun newFileWriter(name: String, append: Boolean): FileWriter { guard(GuardCategory.FILE_WRITE, name); return FileWriter(name, append) }
    @JvmStatic fun filesNewOutputStream(p: Path, opts: Array<out OpenOption>): OutputStream { guard(GuardCategory.FILE_WRITE, p.toString()); return java.nio.file.Files.newOutputStream(p, *opts) }
    @JvmStatic fun filesNewBufferedWriter(p: Path, opts: Array<out OpenOption>): java.io.BufferedWriter { guard(GuardCategory.FILE_WRITE, p.toString()); return java.nio.file.Files.newBufferedWriter(p, *opts) }
    @JvmStatic fun filesNewBufferedWriter(p: Path, cs: Charset, opts: Array<out OpenOption>): java.io.BufferedWriter { guard(GuardCategory.FILE_WRITE, p.toString()); return java.nio.file.Files.newBufferedWriter(p, cs, *opts) }
    @JvmStatic fun filesWrite(p: Path, bytes: ByteArray, opts: Array<out OpenOption>): Path { guard(GuardCategory.FILE_WRITE, p.toString()); return java.nio.file.Files.write(p, bytes, *opts) }
    @JvmStatic fun filesWrite(p: Path, lines: Iterable<CharSequence>, opts: Array<out OpenOption>): Path { guard(GuardCategory.FILE_WRITE, p.toString()); return java.nio.file.Files.write(p, lines, *opts) }
    @JvmStatic fun filesWriteString(p: Path, csq: CharSequence, opts: Array<out OpenOption>): Path { guard(GuardCategory.FILE_WRITE, p.toString()); return java.nio.file.Files.writeString(p, csq, *opts) }
    @JvmStatic fun filesDelete(p: Path) { guard(GuardCategory.FILE_WRITE, p.toString()); java.nio.file.Files.delete(p) }
    @JvmStatic fun filesDeleteIfExists(p: Path): Boolean { guard(GuardCategory.FILE_WRITE, p.toString()); return java.nio.file.Files.deleteIfExists(p) }
    @JvmStatic fun newRandomAccessFile(f: File, mode: String): RandomAccessFile { guard(if (mode == "r") GuardCategory.FILE_READ else GuardCategory.FILE_WRITE, f.path); return RandomAccessFile(f, mode) }
    @JvmStatic fun newRandomAccessFile(name: String, mode: String): RandomAccessFile { guard(if (mode == "r") GuardCategory.FILE_READ else GuardCategory.FILE_WRITE, name); return RandomAccessFile(name, mode) }

    // ---- reflection ----
    @JvmStatic fun classForName(name: String): Class<*> { guard(GuardCategory.REFLECTION, name); return Class.forName(name) }
    @JvmStatic fun classForName(name: String, init: Boolean, loader: ClassLoader?): Class<*> { guard(GuardCategory.REFLECTION, name); return Class.forName(name, init, loader) }
    @JvmStatic fun invoke(m: Method, obj: Any?, args: Array<Any?>?): Any? { guard(GuardCategory.REFLECTION, m.toString()); return m.invoke(obj, *(args ?: emptyArray())) }
    @JvmStatic fun newInstance(c: Constructor<*>, args: Array<Any?>?): Any { guard(GuardCategory.REFLECTION, c.toString()); return c.newInstance(*(args ?: emptyArray())) }
    @JvmStatic fun setAccessible(o: AccessibleObject, flag: Boolean) { guard(GuardCategory.REFLECTION, o.toString()); o.isAccessible = flag }
    @JvmStatic fun setAccessibleAll(os: Array<AccessibleObject>, flag: Boolean) { guard(GuardCategory.REFLECTION, os.joinToString()); AccessibleObject.setAccessible(os, flag) }

    // ---- process / exec ----
    @JvmStatic fun exec(r: Runtime, cmd: String): Process { guard(GuardCategory.EXEC, cmd); return r.exec(cmd) }
    @JvmStatic fun exec(r: Runtime, cmd: Array<String>): Process { guard(GuardCategory.EXEC, cmd.joinToString(" ")); return r.exec(cmd) }
    @JvmStatic fun exec(r: Runtime, cmd: String, env: Array<String>?): Process { guard(GuardCategory.EXEC, cmd); return r.exec(cmd, env) }
    @JvmStatic fun exec(r: Runtime, cmd: Array<String>, env: Array<String>?): Process { guard(GuardCategory.EXEC, cmd.joinToString(" ")); return r.exec(cmd, env) }
    @JvmStatic fun pbStart(pb: ProcessBuilder): Process { guard(GuardCategory.EXEC, pb.command().joinToString(" ")); return pb.start() }
}
