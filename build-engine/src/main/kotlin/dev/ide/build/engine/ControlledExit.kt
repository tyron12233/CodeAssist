package dev.ide.build.engine

/**
 * Thrown by the run's bytecode bridge when the interpreted program calls `System.exit`/`Runtime.exit`/
 * `Runtime.halt`. It is an [Error] (not an [Exception]) so a user `catch (Exception)` won't swallow it; the
 * program interpreter catches it to end the *run* with [code] — instead of a real `exit` terminating the whole
 * IDE process (the program runs in-process on the bytecode VM, where a `SecurityManager` exit trap is
 * unsupported on ART).
 */
class ControlledExit(val code: Int) : Error("exit($code)")
