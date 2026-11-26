// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.playground.ui

import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

// The engine (parser, symbols, completion, diagnostics, the VM that runs a program) lives in a Web Worker, built
// by :playground-spike, so none of it blocks the UI thread. This is the UI side of that protocol: one message in,
// one message out, matched by id. The wire format is the worker's: see its Worker.kt.

@JsFun("(url, handler) => { const w = new Worker(url, { type: 'module' }); w.onmessage = (e) => handler(e.data.type, e.data.id ?? 0, e.data.a ?? '', e.data.b ?? '', e.data.c ?? '', e.data.d ?? ''); w.onerror = (e) => handler('fatal', 0, String((e && e.message) || 'worker failed') + (e && e.error && e.error.stack ? ' @ ' + String(e.error.stack).split('\\n').slice(0, 8).map((l) => l.trim().replace(/https?:[^ )]*\\//, '')).join(' | ') : ''), '', '', ''); return w; }")
private external fun createWorker(url: String, handler: (String, Int, String, String, String, String) -> Unit): JsAny

@JsFun("(w, type, id, source, offset, base, end, path) => w.postMessage({ type, id, source, offset, base, end, path })")
private external fun postToWorker(w: JsAny, type: String, id: Int, source: String, offset: Int, base: String, end: Int, path: String)

@JsFun("(w) => w.terminate()")
private external fun terminateWorker(w: JsAny)

@JsFun("() => new URL('./jars' + (window.PLAYGROUND_ASSETS ? '-' + window.PLAYGROUND_ASSETS : '') + '/', location.href).href")
private external fun jarsBaseUrl(): String

@JsFun("() => new URL('./worker' + (window.PLAYGROUND_ASSETS ? '-' + window.PLAYGROUND_ASSETS : '') + '/codeassist-playground-spike.mjs', location.href).href")
private external fun workerUrl(): String

/** The page's loading screen (index.html): [text] is a stage change ("app", "boot", "libs 3 12 name", "indexing", "ready", "failed ..."). */
@JsFun("(text) => { if (window.playgroundStatus) window.playgroundStatus(text); }")
external fun showLoading(text: String)

/** What a run produced: stdout, the entry's value, the error (empty when none), and "lower/run" milliseconds. */
class RunResult(val stdout: String, val result: String, val error: String, val timing: String)

class EngineClient(
    private val onStatus: (String) -> Unit,
    private val onReady: () -> Unit = {},
    /** The engine went away (a [stop], or it failed): it is not ready until [onReady] fires again. */
    private val onLost: () -> Unit = {},
) {
    private var nextId = 1
    private val waiting = HashMap<Int, CancellableContinuation<Array<String>>>()
    /** What each unanswered request is (for the report when the worker dies). */
    private val inFlight = HashMap<Int, String>()
    private var worker: JsAny? = null
    private var ready: CancellableContinuation<Unit>? = null
    var isReady = false
        private set

    /** Why the worker last died (its error event's message), for a run that was lost with it. */
    private var lastFatal = ""

    fun start() {
        worker = createWorker(workerUrl()) { type, id, a, b, c, d ->
            when (type) {
                // The worker's handler is installed: only now may it be spoken to (see Worker.kt).
                "boot" -> { showLoading("boot"); postToWorker(worker!!, "init", 0, "", 0, jarsBaseUrl(), 0, "") }
                "status" -> { showLoading(a); onStatus(a) }
                "ready" -> { showLoading("ready"); isReady = true; onStatus("ready (engine loaded in ${a.toLongOrNull()?.div(100)?.div(10.0) ?: a} s)"); ready?.resume(Unit); ready = null; onReady() }
                "fatal" -> {
                    showLoading("failed $a")
                    lastFatal = a + " [in flight: " + inFlight.values.joinToString() + "]"; onStatus("engine failed: $a"); failAll()
                    // A worker that was up and died (a crash mid-run) is replaced; one that never came up is not retried.
                    if (isReady) stop()
                }
                else -> { inFlight.remove(id); waiting.remove(id)?.resume(arrayOf(a, b, c, d)) }
            }
        }
    }

    /**
     * Stop whatever the engine is doing, by throwing the worker away and starting another: the interpreter cannot be
     * interrupted from outside, and a worker is cheap to replace (the jars are in the HTTP cache). Anything waiting
     * for an answer gets an empty one.
     */
    fun stop() {
        worker?.let { terminateWorker(it) }
        worker = null
        isReady = false
        failAll()
        onLost()
        onStatus("restarting the engine...")
        start()
    }

    private fun failAll() {
        val pending = waiting.values.toList()
        waiting.clear(); inFlight.clear()
        pending.forEach { it.resume(arrayOf("", "", "", "")) }
    }

    private suspend fun ask(type: String, path: String, source: String, offset: Int, end: Int = 0, method: String = ""): Array<String> = suspendCancellableCoroutine { k ->
        val id = nextId++
        waiting[id] = k
        inFlight[id] = "$type $path"
        k.invokeOnCancellation { waiting.remove(id) }
        postToWorker(worker ?: return@suspendCancellableCoroutine k.resume(arrayOf("", "", "", "")), type, id, source, offset, method, end, path)
    }

    /** Make [path] part of the project, with [text], so the language service sees it from every other file. No reply. */
    fun put(path: String, text: String) {
        if (isReady) postToWorker(worker ?: return, "put", 0, text, 0, "", 0, path)
    }

    /** Drop [path] from the project. No reply. */
    fun remove(path: String) {
        if (isReady) postToWorker(worker ?: return, "remove", 0, "", 0, "", 0, path)
    }

    /** The completion reply's JSON (see Worker.kt) for [path], or "" if the engine is not up. */
    suspend fun complete(path: String, source: String, offset: Int): String = if (!isReady) "" else ask("complete", path, source, offset)[0]

    /** The diagnostics reply's JSON, or "" if the engine is not up. */
    suspend fun diagnose(path: String, source: String): String = if (!isReady) "" else ask("diagnose", path, source, 0)[0]

    /** The semantic-tokens reply's JSON: `[from, to, kind, "MODIFIERS"]` per token; "" if the engine is not up. */
    suspend fun semantic(path: String, source: String): String = if (!isReady) "" else ask("semantic", path, source, 0)[0]

    /** The inlay-hints reply's JSON for `[start, end)`; "" if the engine is not up. */
    suspend fun hints(path: String, source: String, start: Int, end: Int): String = if (!isReady) "" else ask("hints", path, source, start, end)[0]

    /** One of the engine's small answers (`folds`, `signature`, `quickdoc`, `format`, `imports`, `definition`, `expand`) as JSON; "" if the engine is not up. */
    suspend fun call(method: String, path: String, source: String, start: Int = 0, end: Int = 0): String =
        if (!isReady) "" else ask("call", path, source, start, end, method)[0]

    /** Run the program whose `fun main()` is in [path] (current text [source]); the other files are the ones [put] gave it. */
    suspend fun run(path: String, source: String): RunResult {
        if (!isReady) return RunResult("", "", "The engine is still loading.", "")
        val r = ask("run", path, source, 0)
        // A real answer always carries its "lower/run" timing. An empty one is failAll(): the worker died (a stack
        // overflow is an uncatchable RangeError, or it ran out of memory) or was stopped, and that is not "no output".
        if (r[3].isEmpty()) {
            val why = lastFatal.ifEmpty { "the engine stopped before the program finished" }
            lastFatal = ""
            return RunResult("", "", "The run was lost: $why. The engine is restarting; run again.", "")
        }
        return RunResult(r[0], r[1], r[2], r[3])
    }
}
