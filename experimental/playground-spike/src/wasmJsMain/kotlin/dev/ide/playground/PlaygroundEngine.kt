// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.playground

import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.interp.PreviewWire
import dev.ide.lang.kotlin.interp.reachableSourceFunctions
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.platform.writeFileAtomically
import dev.ide.vfs.VirtualFile
import dev.ide.vm.ClassPath
import dev.ide.vm.Vm
import kotlin.time.TimeSource

/** What one run of a program produced. [error] is null when `main` returned normally. */
class RunOutcome(
    val stdout: String,
    val result: String?,
    val error: String?,
    val lowerMillis: Long,
    val runMillis: Long,
    /** Bytecode instructions the VM executed for the run: how much interpreting a program cost, whatever the clock says. */
    val vmSteps: Long = 0,
    /** With `profile = true`: the methods that executed the most bytecode, with the instructions run in each. */
    val hot: List<Pair<String, Long>> = emptyList(),
)

/**
 * The Playground's engine: Kotlin source in, program output out, with no JVM.
 *
 *   1. parse + lower natively, against the real kotlin-stdlib read from the file system;
 *   2. encode the lowered program as the wire blob;
 *   3. interpret it with `:interp-core`, which `:jvm-vm` runs from [classpath]'s jars.
 *
 * [classpath] names files already in the in-memory file system (the host mounts them: a `fetch` in the
 * browser, the disk under Node). It must contain the kotlin-stdlib and the jars of `:interp-core`.
 */
/** Safari and every browser on iOS: the engine behind them is WebKit's, whose workers have a small stack. */
@JsFun("() => { const u = navigator.userAgent; return /iPhone|iPad|iPod/.test(u) || (/AppleWebKit/.test(u) && !/Chrome\\/|Chromium|Edg\\//.test(u)); }")
private external fun isWebKit(): Boolean

class PlaygroundEngine(
    private val classpath: List<String>,
    /** Wall-clock a program may run, per loop and in all. */
    private val budgetMillis: Long = 20_000,
    /**
     * How deep interpreted calls may nest before the run is stopped with a clear error. A worker's stack is small
     * and overflowing it is an uncatchable `RangeError`, so this is a margin below what fits. The VM runs a call
     * to another bytecode method without recursing on the host, but natives and the host's own calls into the VM
     * still nest. Measured: Chrome (about 1 MB) takes well over 80; WebKit's workers (Safari, every iOS browser)
     * have about a quarter of that, and 40 nested calls ran there while 79 did not. The page restarts the worker
     * if something still gets through.
     */
    private val callDepth: Int = if (isWebKit()) 30 else 80,
) {
    // Opening a jar is the expensive part of a VM's first class load; the opened archives are read-only and
    // are shared by every run's VM (each run still gets a fresh VM, so statics start clean).
    private val classPath = ClassPath(classpath)
    private val stdlib = classpath.single { it.substringAfterLast('/').startsWith("kotlin-stdlib-") }

    /** Run `/project/Main.kt` (a single-file program). */
    fun run(source: String, profile: Boolean = false): RunOutcome = run(DEFAULT_ENTRY, source, profile)

    /**
     * Run the program whose `fun main()` is in [entryPath] (whose current text is [source]). The rest of the
     * project is read from the in-memory file system, where the host keeps every file's latest text.
     */
    fun run(entryPath: String, source: String, profile: Boolean = false): RunOutcome {
        val out = StringBuilder()
        val start = TimeSource.Monotonic.markNow()
        var lowered = 0L
        return try {
            val blob = lower(entryPath, source)
            lowered = start.elapsedNow().inWholeMilliseconds
            val vm = Vm(classPath, stdout = { out.append(it) })
            val result = vm.invokeStatic("dev/ide/interp/VmProgramEntry", "run", "([BJI)Ljava/lang/String;", blob, budgetMillis, callDepth) as String
            RunOutcome(out.toString(), result, null, lowered, start.elapsedNow().inWholeMilliseconds - lowered, vm.steps, if (profile) vm.hotMethods(30) else emptyList())
        } catch (e: Throwable) {
            RunOutcome(out.toString(), null, e.message ?: e.toString(), lowered, start.elapsedNow().inWholeMilliseconds - lowered)
        }
    }

    /**
     * Lower the program that starts at `fun main()` in [entryPath] to the wire blob: that file, and every file of the
     * project it reaches (the lowering that previews use to follow a call or a type into another file).
     */
    private fun lower(entryPath: String, source: String): ByteArray {
        check(writeFileAtomically(entryPath, source.encodeToByteArray())) { "could not write $entryPath" }
        val kt = KotlinParserHost.parse(entryPath.substringAfterLast('/'), source)
        val service = KotlinSymbolService(listOf<VirtualFile>(MemoryFile(PROJECT_ROOT)), listOf(stdlib))
        val parsed = KotlinParsedFile(kt, MemoryFile(entryPath), 0)
        val model = KotlinPreviewLowering(service).crossFileModel(parsed)
        val main = model.program["main/0"] ?: error("no `fun main()` in ${entryPath.substringAfterLast('/')}")
        // Everything `main` can reach has to be interpretable, or the run would die half way through.
        val broken = reachableSourceFunctions(main, model.program, model.classes).filterNot { it.isComplete }
        check(broken.isEmpty()) {
            "${broken.first().name} could not be interpreted: " + broken.first().diagnostics.joinToString("; ") { it.reason }
        }
        return PreviewWire.encode(main, model.program, model.classes)
    }

    private companion object {
        const val PROJECT_ROOT = "/project"
        const val DEFAULT_ENTRY = "/project/Main.kt"
    }
}
