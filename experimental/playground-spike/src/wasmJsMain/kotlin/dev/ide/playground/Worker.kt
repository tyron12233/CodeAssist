// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.playground

import dev.ide.platform.MemoryFileSystem
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// ---- the worker's side of the page <-> worker protocol ----------------------------------------------------
//
//   worker -> page   { type: "boot" }                the worker's handler is installed: only now may the page talk
//                                                    to it (a module worker finishes loading asynchronously, and a
//                                                    message posted earlier finds no handler and is dropped)
//   page -> worker   { type: "init", base }          fetch <base>manifest.json and every jar it lists
//                    { type: "put", path, source }  /  { type: "remove", path }   the project's files (no reply)
//                    { type: "run", id, path, source }   run the program whose main() is in `path`
//                    Every request below names its file in `path` ("/project/Main.kt" when it does not).
//                    { type: "complete", id, source, offset }   completions at a caret offset
//                    { type: "diagnose", id, source }           the file's diagnostics
//                    { type: "semantic", id, source }           type-aware colouring: what each identifier IS
//                    { type: "hints", id, source, offset, end } inlay hints for [offset, end)
//                    { type: "call", id, base: method, source, offset, end }  folds, signature, quickdoc, format,
//                                                       imports, definition, expand: one JSON answer (see EditorSession.call)
//   worker -> page   { type: "status", a }           progress text
//                    { type: "ready", a: millis }    the engine is loaded
//                    { type: "result", id, a: stdout, b: result, c: error, d: "lower/run millis" }
//                    { type: "completions", id, a: <json> }  /  { type: "diagnostics", id, a: <json> }
//                    { type: "reply", id, a: <json> }        the answer to a "call"
//                    { type: "fatal", a }            the worker itself failed
//
// Everything here is Kotlin; only the transport (message events, `fetch`) is JS, and it is kept to the few
// lines below. A jar crosses as one base64 string: copying an ArrayBuffer element by element would be
// millions of interop calls, and a native `FileReader.readAsDataURL` does the encoding for free.

@JsFun("(handler) => { self.onmessage = (e) => handler(e.data.type, e.data.id ?? 0, e.data.base ?? '', e.data.source ?? '', e.data.offset ?? 0, e.data.end ?? 0, e.data.path ?? ''); }")
private external fun onMessage(handler: (String, Int, String, String, Int, Int, String) -> Unit)

@JsFun("(type, id, a, b, c, d) => self.postMessage({ type, id, a, b, c, d })")
private external fun post(type: String, id: Int, a: String, b: String, c: String, d: String)

/**
 * Fetch `<base>manifest.json` (a JSON array of file names) and every file in it, calling [onFile] with each one's
 * name and base64 content, then [onDone] (or [onError]). Files are fetched one at a time: the browser would
 * happily run them in parallel, but each is held as a string while it is decoded and the peak is what matters.
 */
@JsFun(
    """(base, onFile, onDone, onError) => {
    (async () => {
        const names = await (await fetch(base + 'manifest.json')).json();
        for (const name of names) {
            const res = await fetch(base + name);
            if (!res.ok) throw new Error('fetch ' + name + ': HTTP ' + res.status);
            const blob = await res.blob();
            const b64 = await new Promise((ok, fail) => {
                const r = new FileReader();
                r.onload = () => ok(r.result.substring(r.result.indexOf(',') + 1));
                r.onerror = () => fail(r.error);
                r.readAsDataURL(blob);
            });
            onFile(name, b64);
        }
        onDone();
    })().catch((e) => onError(String(e && e.message || e)));
}""",
)
private external fun fetchAll(base: String, onFile: (String, String) -> Unit, onDone: () -> Unit, onError: (String) -> Unit)

private const val CLASSPATH_DIR = "/classpath"
private var engine: PlaygroundEngine? = null
private var editor: EditorSession? = null
private val scope = CoroutineScope(Dispatchers.Default)

/** The jars a PROGRAM may use, and so the editor completes against: the standard library and coroutines. */
private fun isUserLibrary(path: String): Boolean {
    val name = path.substringAfterLast('/')
    return name.startsWith("kotlin-stdlib-") || name.startsWith("kotlinx-coroutines-core-jvm-")
}

@OptIn(ExperimentalEncodingApi::class)
private fun init(base: String) {
    val started = TimeSource.Monotonic.markNow()
    val paths = ArrayList<String>()
    fetchAll(
        base,
        onFile = { name, b64 ->
            val path = "$CLASSPATH_DIR/$name"
            MemoryFileSystem.write(path, Base64.decode(b64))
            paths.add(path)
            post("status", 0, "loaded $name", "", "", "")
        },
        onDone = {
            try {
                engine = PlaygroundEngine(paths)
                editor = EditorSession(paths.filter(::isUserLibrary))
                post("ready", 0, started.elapsedNow().inWholeMilliseconds.toString(), "", "", "")
            } catch (e: Throwable) {
                post("fatal", 0, e.message ?: e.toString(), "", "", "")
            }
        },
        onError = { post("fatal", 0, it, "", "", "") },
    )
}

/** The file a request is about: the one it names, or `Main.kt` for a page that never says (the CodeMirror page). */
private fun pathOf(path: String): String = path.ifEmpty { "/project/Main.kt" }

private fun run(id: Int, path: String, source: String) {
    val e = engine ?: return post("fatal", id, "run before ready", "", "", "")
    val outcome = e.run(pathOf(path), source)
    post("result", id, outcome.stdout, outcome.result ?: "", outcome.error ?: "", "${outcome.lowerMillis}/${outcome.runMillis}")
}

private fun complete(id: Int, path: String, source: String, offset: Int) {
    val session = editor ?: return post("fatal", id, "complete before ready", "", "", "")
    scope.launch {
        try {
            val r = session.complete(pathOf(path), source, offset)
            post("completions", id, completionsJson(r), "", "", "")
        } catch (e: Throwable) {
            post("completions", id, """{"from":$offset,"to":$offset,"items":[],"error":${jsonString(e.message ?: e.toString())}}""", "", "", "")
        }
    }
}

private fun diagnose(id: Int, path: String, source: String) {
    val session = editor ?: return post("fatal", id, "diagnose before ready", "", "", "")
    val json = try {
        diagnosticsJson(session.diagnose(pathOf(path), source))
    } catch (e: Throwable) {
        "[]"
    }
    post("diagnostics", id, json, "", "", "")
}

private fun call(id: Int, method: String, path: String, source: String, start: Int, end: Int) {
    val session = editor ?: return post("fatal", id, "call before ready", "", "", "")
    scope.launch {
        val json = try { session.call(method, pathOf(path), source, start, end) } catch (e: Throwable) { "null" }
        post("reply", id, json, "", "", "")
    }
}

private fun semantic(id: Int, path: String, source: String) {
    val session = editor ?: return post("fatal", id, "semantic before ready", "", "", "")
    scope.launch {
        val json = try { tokensJson(session.semanticTokens(pathOf(path), source)) } catch (e: Throwable) { "[]" }
        post("semantic", id, json, "", "", "")
    }
}

private fun hints(id: Int, path: String, source: String, start: Int, end: Int) {
    val session = editor ?: return post("fatal", id, "hints before ready", "", "", "")
    scope.launch {
        val json = try { hintsJson(session.inlayHints(pathOf(path), source, start, end)) } catch (e: Throwable) { "[]" }
        post("hints", id, json, "", "", "")
    }
}

fun main() {
    onMessage { type, id, base, source, offset, end, path ->
        try {
            when (type) {
                "init" -> init(base)
                "run" -> run(id, path, source)
                "complete" -> complete(id, path, source, offset)
                "diagnose" -> diagnose(id, path, source)
                "semantic" -> semantic(id, path, source)
                "hints" -> hints(id, path, source, offset, end)
                "call" -> call(id, base, path, source, offset, end)
                // The project's files, kept current so the language service sees every one of them.
                "put" -> editor?.put(path, source)
                "remove" -> editor?.remove(path)
            }
        } catch (e: Throwable) {
            post("fatal", id, e.message ?: e.toString(), "", "", "")
        }
    }
    post("boot", 0, "", "", "", "")
}
