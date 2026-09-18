package dev.ide.lang.kotlin.symbols

/** `StackOverflowError` and `OutOfMemoryError`, which is what the analyzer can actually hit. */
internal actual fun isResourceExhaustion(e: Throwable): Boolean = e is VirtualMachineError
