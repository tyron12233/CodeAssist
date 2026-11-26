package dev.ide.interp

import dev.ide.lang.kotlin.interp.PreviewWire

/**
 * A lowered program run to completion with nothing but this module and the standard library: decode the blob,
 * interpret its entry function, answer with what it returned.
 *
 * Like `VmPreviewEntry` for Compose, this is meant to run INSIDE `:jvm-vm`, which is how a host with no JVM
 * (the browser Playground) runs source: the host parses and lowers natively, hands this the wire form, and the
 * interpreter's reflection finds the program's libraries on the VM's class path. Anything the program prints
 * goes to the VM's `System.out`, which the host owns.
 */
object VmProgramEntry {

    /**
     * The entry function in [blob] (a [PreviewWire] blob whose entry takes no arguments, `fun main()`),
     * run to completion. Returns the entry's value as text: `kotlin.Unit` for a plain `main`.
     *
     * The run is bounded by [budgetMillis] of wall-clock time, per loop and for the whole run, and by
     * [callDepth] nested interpreted calls. There is no iteration cap: a loop's budget is time, not count.
     */
    @JvmStatic
    fun run(blob: ByteArray, budgetMillis: Long, callDepth: Int): String {
        val (entry, program, classes) = PreviewWire.decode(blob)
        val nanos = budgetMillis * 1_000_000L
        val limits = RunLimits(loopIterations = Int.MAX_VALUE, loopNanos = nanos, passNanos = nanos, callDepth = callDepth)
        return Interpreter(program, classes = classes, limits = limits).call(entry, emptyList()).toString()
    }
}
