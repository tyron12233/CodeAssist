package dev.codeassist.swift

import dev.ide.lang.LanguageId
import dev.ide.lsp.LanguageServerConnection
import dev.ide.lsp.LanguageServerContext
import dev.ide.lsp.LanguageServerDefinition
import dev.ide.platform.log.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * Swift's language server: Apple's sourcekit-lsp (completion, diagnostics as you type, hover, go to definition), run
 * from the toolchain the plugin carries, with sourcekitd in process.
 *
 * sourcekit-lsp would read a package's targets from SwiftPM, which compiles `Package.swift` with tools the plugin does
 * not carry; instead it is given a compilation database (`.build/codeassist/compile_commands.json`, SwiftPM's own
 * build directory): one module per directory under `Sources/`, compiled against the device's SDK and with the plugin's
 * SwiftUI on the import path, so `import SwiftUI` completes against the API the previews implement.
 */
class SwiftLanguageServer(private val toolchain: SwiftToolchain, private val log: Logger) : LanguageServerDefinition {
    override val id = "swift"
    override val displayName = "SourceKit-LSP"
    override val languages = setOf(LanguageId(SwiftPlugin.LANGUAGE))

    override fun start(context: LanguageServerContext): LanguageServerConnection? {
        val status = toolchain.prepare()
        if (status is SwiftToolchain.Status.Unavailable) {
            log.warn(status.reason)
            return null
        }
        val server = toolchain.languageServer ?: run {
            log.warn("this build of the Swift plugin carries no sourcekit-lsp")
            return null
        }
        val toolchainDir = toolchain.languageServerToolchain() ?: return null
        runCatching { writeCompilationDatabase(context.root) }
            .onFailure { context.logger.warn("could not write the compilation database: ${it.message}") }
        return LanguageServerConnection.process(
            listOf(
                server.toString(),
                "--default-workspace-type", "compilationDatabase",
                "--compilation-db-search-path", SwiftPackages.BUILD_DIR,
            ),
            context.root,
            toolchain.environment() + mapOf("SOURCEKIT_TOOLCHAIN_PATH" to toolchainDir.toString()),
        )
    }

    /** The package a file belongs to (the nearest directory with a `Package.swift`), else the project. */
    override fun rootFor(file: Path, context: LanguageServerContext): Path? {
        var dir: Path? = file.parent
        while (dir != null && dir.startsWith(context.projectRoot)) {
            if (Files.isRegularFile(dir.resolve(SwiftPackages.MANIFEST))) return dir
            dir = dir.parent
        }
        return context.projectRoot
    }

    /** Writes [root]'s compilation database: every module's files, each with the command that compiles its module. */
    fun writeCompilationDatabase(root: Path) {
        val entries = JSONArray()
        for ((module, files) in modules(root)) {
            val arguments = JSONArray().apply {
                toolchain.compileArguments(module, files).forEach { put(it) }
            }
            for (file in files) {
                entries.put(JSONObject().put("directory", root.toString()).put("file", file.toString()).put("arguments", arguments))
            }
        }
        val out = root.resolve(SwiftPackages.BUILD_DIR).resolve("compile_commands.json")
        val text = entries.toString(1)
        if (Files.isRegularFile(out) && String(Files.readAllBytes(out)) == text) return
        Files.createDirectories(out.parent)
        Files.write(out, text.toByteArray())
    }

    companion object {
        /**
         * The modules of the package at [root]: each directory under `Sources/` with its `.swift` files; a project
         * without that layout is one module of every `.swift` file outside build and hidden directories.
         */
        fun modules(root: Path): Map<String, List<Path>> {
            val sources = root.resolve(SwiftPackages.SOURCES)
            if (Files.isDirectory(sources)) {
                val targets = Files.list(sources).use { s -> s.filter { Files.isDirectory(it) }.sorted().collect(Collectors.toList()) }
                return targets.associate { dir ->
                    SwiftPackages.moduleName(dir.fileName.toString()) to SwiftPackages.sources(root, dir.fileName.toString())
                }.filterValues { it.isNotEmpty() }
            }
            val files = Files.walk(root).use { s ->
                s.filter { p ->
                    Files.isRegularFile(p) && p.fileName.toString().endsWith(".swift") &&
                        root.relativize(p).none { it.toString().startsWith(".") || it.toString() == "build" }
                }.sorted().collect(Collectors.toList())
            }
            return if (files.isEmpty()) emptyMap() else mapOf(SwiftPackages.moduleName(root.fileName?.toString() ?: "main") to files)
        }
    }
}
