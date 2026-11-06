package dev.ide.core.lsp

import dev.ide.core.ApplicationEnvironment
import dev.ide.lang.FILE_TYPE_EP
import dev.ide.lang.FileTypeMapping
import dev.ide.lang.LanguageId
import dev.ide.lsp.LANGUAGE_SERVER_EP
import dev.ide.lsp.LanguageServerConnection
import dev.ide.lsp.LanguageServerContext
import dev.ide.lsp.LanguageServerDefinition
import dev.ide.platform.Disposable
import dev.ide.platform.PluginId
import dev.ide.platform.log.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * A language server the user configured in Settings rather than one a plugin ships: a command they already
 * have installed (gopls, rust-analyzer, pyright, clangd, ...), the file extensions it serves, and the
 * protocol's language id. [id] is stable across edits, so a session keyed on it can be restarted in place.
 */
data class UserLanguageServer(
    val id: String,
    val name: String,
    val command: String,
    val extensions: List<String>,
    val languageId: String,
    val enabled: Boolean = true,
    /** `initializationOptions` as JSON text, or blank for none. */
    val initializationOptions: String = "",
)

/**
 * The user's own language servers: persisted in [file] (JSON), registered on the app registry as a
 * file-type mapping plus a [LanguageServerDefinition] each, under [PLUGIN], and handed to the
 * [LanguageServerBridge] like any plugin's. An edit re-registers them all at once; a project opened afterwards
 * serves the new set, and a server whose command changed is restarted so the open project uses it too.
 *
 * Useful on a desktop, where these servers are installed. On Android an app may only execute what an installed
 * package ships, so a command there must point into one (a plugin's native library directory).
 */
class UserLanguageServers internal constructor(private val env: ApplicationEnvironment, private val file: Path) {

    private val log = Log.logger("lsp.user")
    private val lock = Any()
    private var servers: List<UserLanguageServer> = load()
    private var registrations: List<Disposable> = emptyList()

    fun list(): List<UserLanguageServer> = synchronized(lock) { servers }

    /** Add or replace [server] (matched by id). Returns why it was refused, or null once saved and applied. */
    fun save(server: UserLanguageServer): String? {
        val normalized = server.copy(
            id = server.id.ifBlank { newId(server.name) },
            name = server.name.trim(),
            command = server.command.trim(),
            extensions = server.extensions.mapNotNull(::normalizeExtension).distinct(),
            languageId = server.languageId.trim().lowercase(),
            initializationOptions = server.initializationOptions.trim(),
        )
        validate(normalized)?.let { return it }
        synchronized(lock) {
            val others = servers.filter { it.id != normalized.id }
            others.filter { it.enabled && normalized.enabled }.forEach { other ->
                val shared = other.extensions.intersect(normalized.extensions.toSet())
                if (shared.isNotEmpty()) return "${shared.joinToString()} is already served by ${other.name}."
            }
            val previous = servers.firstOrNull { it.id == normalized.id }
            servers = if (previous == null) servers + normalized else servers.map { if (it.id == normalized.id) normalized else it }
            persist()
            apply()
            // A changed command reaches the open project's session too: the next request restarts it.
            if (previous != null && previous != normalized) env.languageServers.restart(definitionId(normalized.id))
        }
        return null
    }

    fun delete(id: String) {
        synchronized(lock) {
            if (servers.none { it.id == id }) return
            servers = servers.filter { it.id != id }
            persist()
            apply()
        }
    }

    /** How [id]'s server is doing in the open project (see [LanguageServerBridge.status]). */
    fun status(id: String): String = env.languageServers.status(definitionId(id))

    /** Where [command]'s executable resolves to, or a sentence saying why it does not. */
    fun check(command: String): Pair<Boolean, String> {
        val parts = splitCommand(command)
        if (parts.isEmpty()) return false to "Enter a command."
        val resolved = resolveExecutable(parts[0]) ?: return false to "'${parts[0]}' was not found on PATH or in the usual install folders."
        return true to "Found $resolved"
    }

    /** (Re)register every enabled server on the app registry, then let the bridge pick the change up. */
    internal fun apply() {
        synchronized(lock) {
            registrations.forEach { runCatching { it.dispose() } }
            val registry = env.platform.extensions
            registrations = servers.filter { it.enabled }.flatMap { s ->
                listOf(
                    registry.register(FILE_TYPE_EP, FileTypeMapping(s.extensions, LanguageId(s.languageId)), PLUGIN),
                    registry.register(LANGUAGE_SERVER_EP, Definition(s), PLUGIN),
                )
            }
        }
        runCatching { env.languageServers.registerAll() }.onFailure { log.warn("could not apply the language servers", it) }
    }

    private inner class Definition(private val server: UserLanguageServer) : LanguageServerDefinition {
        override val id: String = definitionId(server.id)
        override val displayName: String = server.name
        override val languages: Set<LanguageId> = setOf(LanguageId(server.languageId))

        override fun languageIdFor(path: Path): String =
            LSP_LANGUAGE_IDS[path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase()] ?: server.languageId

        override fun start(context: LanguageServerContext): LanguageServerConnection? {
            val parts = splitCommand(server.command)
            val executable = parts.firstOrNull()?.let(::resolveExecutable) ?: run {
                context.logger.warn("${server.name}: '${parts.firstOrNull()}' was not found")
                return null
            }
            val command = launchCommand(executable, parts.drop(1))
            val path = (listOf(executable.parent?.toString()) + searchPath().map { it.toString() })
                .filterNotNull().distinct().joinToString(File.pathSeparator)
            return LanguageServerConnection.process(command, context.root, mapOf("PATH" to path))
        }

        override fun initializationOptions(context: LanguageServerContext): String? = server.initializationOptions.ifBlank { null }
    }

    // ---------------------------------------------------------------------------------------------- persistence

    private fun load(): List<UserLanguageServer> = runCatching {
        if (!Files.isRegularFile(file)) return emptyList()
        val root = Json.parseToJsonElement(String(Files.readAllBytes(file), Charsets.UTF_8)) as JsonObject
        (root["servers"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            UserLanguageServer(
                id = str("id").ifBlank { return@mapNotNull null },
                name = str("name"),
                command = str("command"),
                extensions = (o["extensions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                languageId = str("languageId"),
                enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                initializationOptions = str("initializationOptions"),
            )
        }
    }.onFailure { log.warn("could not read $file; starting with no language servers", it) }.getOrDefault(emptyList())

    private fun persist() {
        val json = buildJsonObject {
            putJsonArray("servers") {
                for (s in servers) add(buildJsonObject {
                    put("id", s.id)
                    put("name", s.name)
                    put("command", s.command)
                    putJsonArray("extensions") { s.extensions.forEach { add(JsonPrimitive(it)) } }
                    put("languageId", s.languageId)
                    put("enabled", s.enabled)
                    if (s.initializationOptions.isNotBlank()) put("initializationOptions", s.initializationOptions)
                })
            }
        }
        runCatching {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.write(tmp, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json).toByteArray(Charsets.UTF_8))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { log.warn("could not save $file", it) }
    }

    private fun validate(s: UserLanguageServer): String? = when {
        s.name.isBlank() -> "Give the server a name."
        s.command.isBlank() -> "Enter the command that starts the server."
        s.extensions.isEmpty() -> "List at least one file extension, such as .go."
        !Regex("[a-z0-9][a-z0-9_.+-]*").matches(s.languageId) -> "The language id is letters, digits, '.', '_', '+' or '-', such as go."
        s.initializationOptions.isNotBlank() && runCatching { Json.parseToJsonElement(s.initializationOptions) }.isFailure ->
            "Initialization options must be JSON."
        else -> null
    }

    private fun newId(name: String): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "server" }
        var id = base
        var n = 2
        while (servers.any { it.id == id }) id = "$base-${n++}"
        return id
    }

    companion object {
        val PLUGIN = PluginId("user-language-servers")
        const val FILE_NAME = "language-servers.json"

        fun definitionId(id: String) = "user:$id"

        /** Servers people commonly have installed, ready to add: name, command, extensions, language id. */
        val PRESETS: List<UserLanguageServer> = listOf(
            UserLanguageServer("", "Go (gopls)", "gopls", listOf(".go"), "go"),
            UserLanguageServer("", "Rust (rust-analyzer)", "rust-analyzer", listOf(".rs"), "rust"),
            UserLanguageServer("", "Python (Pyright)", "pyright-langserver --stdio", listOf(".py"), "python"),
            UserLanguageServer(
                "", "TypeScript / JavaScript", "typescript-language-server --stdio",
                listOf(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs"), "typescript",
            ),
            UserLanguageServer("", "C / C++ (clangd)", "clangd", listOf(".c", ".cc", ".cpp", ".cxx", ".h", ".hpp"), "cpp"),
            UserLanguageServer("", "Dart", "dart language-server", listOf(".dart"), "dart"),
            UserLanguageServer("", "Lua", "lua-language-server", listOf(".lua"), "lua"),
            UserLanguageServer("", "Zig (zls)", "zls", listOf(".zig"), "zig"),
            UserLanguageServer("", "Bash", "bash-language-server start", listOf(".sh", ".bash"), "shellscript"),
        )

        /** The protocol's language id for an extension whose server serves several. */
        private val LSP_LANGUAGE_IDS = mapOf(
            "ts" to "typescript", "tsx" to "typescriptreact", "js" to "javascript", "jsx" to "javascriptreact",
            "mjs" to "javascript", "cjs" to "javascript", "c" to "c", "h" to "c", "cc" to "cpp", "cpp" to "cpp",
            "cxx" to "cpp", "hpp" to "cpp", "py" to "python", "go" to "go", "rs" to "rust", "dart" to "dart",
        )

        fun normalizeExtension(raw: String): String? {
            val e = raw.trim().removePrefix("*").trim()
            if (e.isEmpty()) return null
            return if (e.startsWith(".")) e.lowercase() else ".${e.lowercase()}"
        }

        /** [command] split like a shell would at its simplest: whitespace, with single or double quotes. */
        fun splitCommand(command: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var quote: Char? = null
            var any = false
            for (c in command.trim()) {
                when {
                    quote != null && c == quote -> quote = null
                    quote != null -> cur.append(c)
                    c == '"' || c == '\'' -> { quote = c; any = true }
                    c.isWhitespace() -> if (cur.isNotEmpty() || any) { out += cur.toString(); cur.clear(); any = false }
                    else -> cur.append(c)
                }
            }
            if (cur.isNotEmpty() || any) out += cur.toString()
            return out
        }

        private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

        /**
         * Where a server's executable is looked for: PATH, then the folders language toolchains install into.
         * A desktop app launched from the Dock or Start menu inherits a minimal PATH, so `gopls` in
         * `~/go/bin` would otherwise never be found although the user's terminal finds it.
         */
        fun searchPath(): List<Path> {
            val home = System.getProperty("user.home").orEmpty()
            val fromEnv = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
            val usual = if (windows) listOf(
                "$home\\AppData\\Roaming\\npm", "$home\\.cargo\\bin", "$home\\go\\bin", "$home\\AppData\\Local\\Pub\\Cache\\bin",
            ) else listOf(
                "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin", "/snap/bin",
                "$home/go/bin", "$home/.cargo/bin", "$home/.local/bin", "$home/.npm-global/bin", "$home/.bun/bin",
                "$home/.deno/bin", "$home/.pub-cache/bin", "$home/.dotnet/tools", "$home/bin",
            )
            return (fromEnv + usual).mapNotNull { runCatching { Paths.get(it) }.getOrNull() }.distinct()
        }

        fun resolveExecutable(name: String): Path? {
            val expanded = if (name.startsWith("~")) System.getProperty("user.home") + name.substring(1) else name
            if (expanded.contains('/') || expanded.contains('\\')) {
                return runCatching { Paths.get(expanded) }.getOrNull()?.takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }
            }
            val suffixes = if (windows) listOf("", ".exe", ".cmd", ".bat") else listOf("")
            for (dir in searchPath()) for (suffix in suffixes) {
                val candidate = dir.resolve(expanded + suffix)
                if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) return candidate
            }
            return null
        }

        /** A Windows `.cmd`/`.bat` shim (what npm installs) has to run through the command interpreter. */
        private fun launchCommand(executable: Path, args: List<String>): List<String> {
            val name = executable.fileName.toString().lowercase()
            return if (windows && (name.endsWith(".cmd") || name.endsWith(".bat"))) listOf("cmd", "/c", executable.toString()) + args
            else listOf(executable.toString()) + args
        }
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
