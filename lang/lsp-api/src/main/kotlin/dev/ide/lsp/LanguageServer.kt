// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.lsp

import dev.ide.lang.LanguageId
import dev.ide.platform.ExtensionPoint
import dev.ide.platform.log.Logger
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * A Language Server Protocol server that serves one or more [languages]. Register it on
 * [LANGUAGE_SERVER_EP] and the IDE does the rest: it starts the server the first time a file of one of those
 * languages is opened in a project, keeps the server's copy of every open buffer in sync, and answers the
 * editor's requests from it. Every editor feature the server advertises is wired up:
 *
 * - diagnostics (pushed or pulled), quick fixes and refactorings (code actions, including server commands);
 * - completion (with snippets and auto-import edits), signature help, hover as Quick Documentation;
 * - go to declaration, implementation and type declaration; find usages and highlight usages; rename;
 * - semantic colouring, folding, inlay hints, the structure view and breadcrumbs, and Reformat Code.
 *
 * A plugin still registers the parts the protocol does not cover: the file extensions that map to the
 * language (`platform.fileType`), and the editor's lexical colouring (`ui.editorLanguage`), which shows
 * before the server has answered and wherever it is unavailable.
 *
 * One server is started per (definition, project root). It is shut down when that project closes, and
 * restarted on the next request if it exits. Only a crash loop (several failures within a few minutes) leaves
 * it down until the project is reopened, which the user is told about once; a server Android killed in the
 * background comes back every time. Since SPI 3.3.0.
 */
interface LanguageServerDefinition {
    /** Stable and unique among definitions, e.g. `"dart"`. Used in logs and as the key for a running server. */
    val id: String

    /** The server's name for the user, e.g. `"Dart analysis server"`. */
    val displayName: String get() = id

    /** The IDE languages this server answers for: files the `platform.fileType` mappings route to these. */
    val languages: Set<LanguageId>

    /**
     * The protocol's `languageId` for the document at [path] (`"dart"`, `"cpp"`, ...), sent with
     * `textDocument/didOpen`. Defaults to the first of [languages], which is right for a server with one
     * language; a server for several (C and C++) answers per file.
     */
    fun languageIdFor(path: Path): String = languages.first().id

    /**
     * Start the server for [context]'s project and return its streams, or null when it cannot run here (its
     * toolchain is not installed yet, the platform has no build of it). Called off the UI thread, at most once
     * per running server; a null is asked again on a later request, so a server that becomes available once a
     * download finishes is picked up without a restart.
     *
     * [LanguageServerConnection.process] covers the usual case of a server that speaks over stdio.
     */
    fun start(context: LanguageServerContext): LanguageServerConnection?

    /** `initializationOptions` for the `initialize` request, as JSON text, or null for none. */
    fun initializationOptions(context: LanguageServerContext): String? = null

    /**
     * The answer to a `workspace/configuration` item for [section] (null asks for the whole configuration),
     * as JSON text, or null for "no configuration", which the protocol sends as `null`.
     */
    fun configuration(section: String?, context: LanguageServerContext): String? = null

    /**
     * The workspace root for [file], when it is not the project root: the directory holding the nearest
     * `pubspec.yaml` or `go.mod`, say, for a server that wants one package per workspace. Files that answer
     * the same root share one server. Null means the open project's root.
     */
    fun rootFor(file: Path, context: LanguageServerContext): Path? = null
}

/** What a [LanguageServerDefinition] is started in. */
interface LanguageServerContext {
    /** The workspace root the server is being started for: the `rootUri` and only workspace folder. */
    val root: Path

    /** The open project's root directory, which [root] is equal to or under. */
    val projectRoot: Path

    /** Where to report what the server does; attributed to the plugin that registered the definition. */
    val logger: Logger
}

/**
 * The two streams the client speaks the protocol over: [input] is what the server writes, [output] what it
 * reads. [errors], when given, is drained to the log so a chatty server never blocks on a full stderr pipe.
 * [close] runs once the session is over, after `shutdown` and `exit` were sent (or the server died).
 */
class LanguageServerConnection @JvmOverloads constructor(
    val input: InputStream,
    val output: OutputStream,
    val errors: InputStream? = null,
    private val onClose: Runnable = Runnable {},
) {
    @Volatile private var closed = false

    /** Release the connection. Idempotent. */
    fun close() {
        if (closed) return
        closed = true
        runCatching { output.close() }
        runCatching { onClose.run() }
        runCatching { input.close() }
        runCatching { errors?.close() }
    }

    companion object {
        /**
         * Run [command] as a child process and speak to it over its stdin and stdout. [workingDirectory]
         * defaults to the inherited one; [environment] is added to the inherited environment.
         *
         * On Android the executable must live in an installed package's native library directory
         * (`PluginRegistration.nativeLibrary`), since an app may not execute a file it wrote itself.
         */
        @JvmStatic
        @JvmOverloads
        fun process(
            command: List<String>,
            workingDirectory: Path? = null,
            environment: Map<String, String> = emptyMap(),
        ): LanguageServerConnection {
            require(command.isNotEmpty()) { "a language server command needs an executable" }
            val builder = ProcessBuilder(command)
            workingDirectory?.let { builder.directory(it.toFile()) }
            if (environment.isNotEmpty()) builder.environment().putAll(environment)
            val process = builder.start()
            return LanguageServerConnection(process.inputStream, process.outputStream, process.errorStream) {
                // `exit` has been sent; give the server a moment to leave on its own before it is killed.
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroy()
                    if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
                }
            }
        }
    }
}

/** Language servers contributed by plugins; see [LanguageServerDefinition]. Since SPI 3.3.0. */
val LANGUAGE_SERVER_EP = ExtensionPoint<LanguageServerDefinition>("platform.languageServer")
