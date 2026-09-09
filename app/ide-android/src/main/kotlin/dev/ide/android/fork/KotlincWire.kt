package dev.ide.android.fork

import dev.ide.lang.kotlin.compile.KotlinCompileRequest
import dev.ide.lang.kotlin.compile.KotlinCompileResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The on-disk encoding of a compile request and its result, shared by [ForkedKotlinCompiler] (writer of the
 * request, reader of the result) and [KotlincWorkerMain] (the reverse).
 *
 * Bulk travels on disk, not through the pipe: the control channel between the two processes carries only a
 * file path per request and a one-line acknowledgement per result. A source list can run to thousands of
 * paths and a diagnostic list to megabytes, and a pipe that must be drained concurrently with a compile is
 * the classic place to deadlock.
 *
 * The format is one `key=value` line per field, repeated for list-valued keys, order-preserving. Values are
 * newline-escaped so a multi-line compiler message cannot inject a line and desynchronize the parse; paths
 * are written verbatim otherwise. Unknown keys are ignored on read, so a worker from a previous app version
 * left running against a newer client degrades to dropping a field rather than failing to parse (the client
 * kills stale workers on version mismatch anyway, see [PROTOCOL_VERSION]).
 */
internal object KotlincWire {

    /**
     * Bumped whenever a key's meaning changes or a field the compile depends on is added. The client sends it
     * on the worker command line and the worker refuses to start on a mismatch, so an app update cannot leave
     * a running worker silently ignoring a new field.
     */
    const val PROTOCOL_VERSION = 1

    // Request keys.
    private const val K_SRC = "src"
    private const val K_JAVA_SRC = "javaSrc"
    private const val K_CLASSPATH = "cp"
    private const val K_OUT = "out"
    private const val K_JVM_TARGET = "jvmTarget"
    private const val K_BOOT = "boot"
    private const val K_FRIEND = "friend"
    private const val K_PLUGIN = "plugin"
    private const val K_PLUGIN_OPT = "pluginOpt"
    private const val K_RUNTIME_PLUGIN = "rtPlugin"

    // Result keys.
    private const val K_SUCCESS = "success"
    private const val K_MESSAGE = "msg"
    private const val K_OUTPUT = "output"

    /** Separates the entries of a single list-of-paths value (a runtime plugin classpath, an output group). */
    private const val UNIT = "\u0001"

    fun writeRequest(request: KotlinCompileRequest, file: Path) {
        val sb = StringBuilder()
        request.kotlinSources.forEach { sb.line(K_SRC, it) }
        request.javaSources.forEach { sb.line(K_JAVA_SRC, it) }
        request.classpath.forEach { sb.line(K_CLASSPATH, it) }
        sb.line(K_OUT, request.outputDir)
        sb.line(K_JVM_TARGET, request.jvmTarget)
        request.bootClasspath.forEach { sb.line(K_BOOT, it) }
        request.friendPaths.forEach { sb.line(K_FRIEND, it) }
        request.compilerPlugins.forEach { sb.line(K_PLUGIN, it) }
        request.pluginOptions.forEach { sb.line(K_PLUGIN_OPT, it) }
        request.runtimePluginClasspaths.forEach { cp -> sb.line(K_RUNTIME_PLUGIN, cp.joinToString(UNIT)) }
        file.parent?.let { Files.createDirectories(it) }
        Files.write(file, sb.toString().toByteArray(Charsets.UTF_8))
    }

    fun readRequest(file: Path): KotlinCompileRequest {
        val fields = parse(file)
        return KotlinCompileRequest(
            kotlinSources = fields.paths(K_SRC),
            javaSources = fields.paths(K_JAVA_SRC),
            classpath = fields.paths(K_CLASSPATH),
            // A request always carries an output dir; an absent one means a corrupt file, which must fail
            // loudly here rather than compile into some default directory.
            outputDir = Paths.get(fields.one(K_OUT) ?: error("compile request has no $K_OUT")),
            jvmTarget = fields.one(K_JVM_TARGET) ?: "17",
            bootClasspath = fields.paths(K_BOOT),
            friendPaths = fields.paths(K_FRIEND),
            compilerPlugins = fields.paths(K_PLUGIN),
            pluginOptions = fields.all(K_PLUGIN_OPT),
            runtimePluginClasspaths = fields.all(K_RUNTIME_PLUGIN)
                .map { cp -> cp.split(UNIT).filter { it.isNotEmpty() }.map(Paths::get) },
        )
    }

    fun writeResult(result: KotlinCompileResult, file: Path) {
        val sb = StringBuilder()
        sb.line(K_SUCCESS, result.success.toString())
        result.messages.forEach { sb.line(K_MESSAGE, it) }
        result.outputs.forEach { (src, classes) ->
            sb.line(K_OUTPUT, (listOf(src.toString()) + classes.map { it.toString() }).joinToString(UNIT))
        }
        file.parent?.let { Files.createDirectories(it) }
        Files.write(file, sb.toString().toByteArray(Charsets.UTF_8))
    }

    fun readResult(file: Path): KotlinCompileResult {
        val fields = parse(file)
        val outputs = LinkedHashMap<Path, List<Path>>()
        for (line in fields.all(K_OUTPUT)) {
            val parts = line.split(UNIT).filter { it.isNotEmpty() }
            if (parts.size < 2) continue
            outputs[Paths.get(parts[0])] = parts.drop(1).map(Paths::get)
        }
        return KotlinCompileResult(
            success = fields.one(K_SUCCESS) == "true",
            messages = fields.all(K_MESSAGE),
            outputs = outputs,
        )
    }

    // --- encoding ------------------------------------------------------------------------------------------

    private fun StringBuilder.line(key: String, value: Any) {
        append(key).append('=').append(escape(value.toString())).append('\n')
    }

    private fun escape(v: String): String = v.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescape(v: String): String {
        if ('\\' !in v) return v
        val sb = StringBuilder(v.length)
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c != '\\' || i == v.length - 1) {
                sb.append(c); i++; continue
            }
            when (val next = v[i + 1]) {
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                '\\' -> sb.append('\\')
                else -> sb.append(c).append(next)
            }
            i += 2
        }
        return sb.toString()
    }

    private class Fields(private val entries: List<Pair<String, String>>) {
        fun all(key: String): List<String> = entries.filter { it.first == key }.map { it.second }
        fun one(key: String): String? = entries.firstOrNull { it.first == key }?.second
        fun paths(key: String): List<Path> = all(key).map(Paths::get)
    }

    private fun parse(file: Path): Fields = Fields(
        Files.readAllLines(file, Charsets.UTF_8).mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) null else line.substring(0, eq) to unescape(line.substring(eq + 1))
        },
    )
}
