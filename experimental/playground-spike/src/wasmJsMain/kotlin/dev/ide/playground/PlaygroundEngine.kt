// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.playground

import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.lang.kotlin.interp.KotlinTreeResolver
import dev.ide.lang.kotlin.interp.PreviewWire
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
class PlaygroundEngine(
    private val classpath: List<String>,
    /** Wall-clock a program may run, per loop and in all. */
    private val budgetMillis: Long = 20_000,
    /**
     * How deep interpreted calls may nest before the run is stopped with a clear error. Measured in Chrome: 100
     * nested calls run and 130 overflow the engine's stack (about 8 KB of wasm stack per interpreted call, and
     * a browser gives a worker roughly 1 MB), which is an uncatchable `RangeError`. So the limit is a margin
     * below that; the page restarts the worker if something still gets through.
     */
    private val callDepth: Int = 80,
) {
    // Opening a jar is the expensive part of a VM's first class load; the opened archives are read-only and
    // are shared by every run's VM (each run still gets a fresh VM, so statics start clean).
    private val classPath = ClassPath(classpath)
    private val stdlib = classpath.single { it.substringAfterLast('/').startsWith("kotlin-stdlib-") }

    fun run(source: String, profile: Boolean = false): RunOutcome {
        val out = StringBuilder()
        val start = TimeSource.Monotonic.markNow()
        var lowered = 0L
        return try {
            val blob = lower(source)
            lowered = start.elapsedNow().inWholeMilliseconds
            val vm = Vm(classPath, stdout = { out.append(it) })
            val result = vm.invokeStatic("dev/ide/interp/VmProgramEntry", "run", "([BJI)Ljava/lang/String;", blob, budgetMillis, callDepth) as String
            RunOutcome(out.toString(), result, null, lowered, start.elapsedNow().inWholeMilliseconds - lowered, vm.steps, if (profile) vm.hotMethods(30) else emptyList())
        } catch (e: Throwable) {
            RunOutcome(out.toString(), null, e.message ?: e.toString(), lowered, start.elapsedNow().inWholeMilliseconds - lowered)
        }
    }

    /** Parse [source] and lower it to the wire blob whose entry is `fun main()`. */
    private fun lower(source: String): ByteArray {
        val dir = "/project"
        val path = "$dir/Main.kt"
        check(writeFileAtomically(path, source.encodeToByteArray())) { "could not write $path" }
        val kt = KotlinParserHost.parse("Main.kt", source)
        val service = KotlinSymbolService(listOf<VirtualFile>(MemoryFile(dir)), listOf(stdlib))
        val parsed = KotlinParsedFile(kt, MemoryFile(path), 0)
        val resolver = KotlinTreeResolver(kt, parsed, service)
        val program = buildMap {
            kt.declarations.filterIsInstance<KtNamedFunction>().forEach { fn ->
                val f = resolver.lowerFunction(fn)
                put("${f.name}/${f.params.size}", f)
            }
            // A top-level `val`/`var` becomes a synthetic `name/0` getter: there is no compiled facade to read.
            kt.declarations.filterIsInstance<KtProperty>().forEach { p ->
                val name = p.name ?: return@forEach
                val hasValue = p.initializer != null || p.getter?.bodyExpression != null || p.getter?.bodyBlockExpression != null
                if (hasValue && !containsKey("$name/0")) put("$name/0", resolver.lowerTopLevelProperty(p))
            }
        }
        val main = program["main/0"] ?: error("no `fun main()` in the source")
        check(main.isComplete) { "main could not be interpreted: " + main.diagnostics.joinToString("; ") { it.reason } }
        return PreviewWire.encode(main, program, resolver.lowerClasses())
    }
}
