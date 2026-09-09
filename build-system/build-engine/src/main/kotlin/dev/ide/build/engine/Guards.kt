package dev.ide.build.engine

/** The kinds of sensitive operation the run-time guard mediates (the categories the permission UI prompts for). */
enum class GuardCategory { NETWORK, FILE_READ, FILE_WRITE, REFLECTION, EXEC }

/**
 * Decides whether a guarded operation may proceed. Implemented by the host (ide-core), which blocks the
 * running program's thread and prompts the user. Consulted by the run's bytecode bridge (which mediates the
 * program's network/file/reflection/exec calls); runs are sequential, so a single process-wide [Guards.broker]
 * is sufficient.
 */
interface PermissionBroker {
    /** Blocking: may the running program perform [category] (with human-readable [detail])? */
    fun check(category: GuardCategory, detail: String): Boolean
}

/**
 * The run sandbox's single enforcement point. The bytecode VM's run bridge calls [enforce] before letting a
 * sensitive platform operation proceed; it consults the current [broker] for the operation's [GuardCategory]
 * and throws [SecurityException] on denial, so the interpreted program sees a normal `SecurityException` it
 * can catch. With no [broker] set (code not running under a guarded run) enforcement is a transparent no-op.
 *
 * This mediates a curated set of common entry points; it is not a hardened sandbox, and native code or an
 * unmediated path can bypass it.
 */
object Guards {
    @Volatile @JvmStatic var broker: PermissionBroker? = null

    /** Throw [SecurityException] if the current [broker] denies [category] (with [detail]); pass when allowed
     *  or when no broker is set. */
    @JvmStatic
    fun enforce(category: GuardCategory, detail: String) {
        val b = broker ?: return
        if (!b.check(category, detail)) throw SecurityException("Blocked ${category.name.lowercase()}: $detail")
    }
}
