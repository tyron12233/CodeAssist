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

@JsFun("(url, handler) => { const w = new Worker(url, { type: 'module' }); w.onmessage = (e) => handler(e.data.type, e.data.id ?? 0, e.data.a ?? '', e.data.b ?? '', e.data.c ?? '', e.data.d ?? ''); w.onerror = (e) => handler('fatal', 0, String((e && e.message) || 'worker failed'), '', '', ''); return w; }")
private external fun createWorker(url: String, handler: (String, Int, String, String, String, String) -> Unit): JsAny

@JsFun("(w, type, id, source, offset, base, end) => w.postMessage({ type, id, source, offset, base, end })")
private external fun postToWorker(w: JsAny, type: String, id: Int, source: String, offset: Int, base: String, end: Int)

@JsFun("() => new URL('./jars/', location.href).href")
private external fun jarsBaseUrl(): String

@JsFun("() => new URL('./worker/codeassist-playground-spike.mjs', location.href).href")
private external fun workerUrl(): String

/** What a run produced: stdout, the entry's value, the error (empty when none), and "lower/run" milliseconds. */
class RunResult(val stdout: String, val result: String, val error: String, val timing: String)

class EngineClient(private val onStatus: (String) -> Unit, private val onReady: () -> Unit = {}) {
    private var nextId = 1
    private val waiting = HashMap<Int, CancellableContinuation<Array<String>>>()
    private var worker: JsAny? = null
    private var ready: CancellableContinuation<Unit>? = null
    var isReady = false
        private set

    fun start() {
        worker = createWorker(workerUrl()) { type, id, a, b, c, d ->
            when (type) {
                // The worker's handler is installed: only now may it be spoken to (see Worker.kt).
                "boot" -> postToWorker(worker!!, "init", 0, "", 0, jarsBaseUrl(), 0)
                "status" -> onStatus(a)
                "ready" -> { isReady = true; onStatus("ready (engine loaded in ${a.toLongOrNull()?.div(100)?.div(10.0) ?: a} s)"); ready?.resume(Unit); ready = null; onReady() }
                "fatal" -> { onStatus("engine failed: $a"); failAll() }
                else -> waiting.remove(id)?.resume(arrayOf(a, b, c, d))
            }
        }
    }

    private fun failAll() {
        val pending = waiting.values.toList()
        waiting.clear()
        pending.forEach { it.resume(arrayOf("", "", "", "")) }
    }

    private suspend fun ask(type: String, source: String, offset: Int, end: Int = 0): Array<String> = suspendCancellableCoroutine { k ->
        val id = nextId++
        waiting[id] = k
        k.invokeOnCancellation { waiting.remove(id) }
        postToWorker(worker!!, type, id, source, offset, "", end)
    }

    /** The completion reply's JSON (see Worker.kt), or "" if the engine is not up. */
    suspend fun complete(source: String, offset: Int): String = if (!isReady) "" else ask("complete", source, offset)[0]

    /** The diagnostics reply's JSON, or "" if the engine is not up. */
    suspend fun diagnose(source: String): String = if (!isReady) "" else ask("diagnose", source, 0)[0]

    /** The semantic-tokens reply's JSON: `[from, to, kind, "MODIFIERS"]` per token; "" if the engine is not up. */
    suspend fun semantic(source: String): String = if (!isReady) "" else ask("semantic", source, 0)[0]

    /** The inlay-hints reply's JSON for `[start, end)`; "" if the engine is not up. */
    suspend fun hints(source: String, start: Int, end: Int): String = if (!isReady) "" else ask("hints", source, start, end)[0]

    suspend fun run(source: String): RunResult {
        if (!isReady) return RunResult("", "", "The engine is still loading.", "")
        val r = ask("run", source, 0)
        return RunResult(r[0], r[1], r[2], r[3])
    }
}
