package dev.ide.interp.impl

import dev.ide.interp.api.BytecodeConfig
import dev.ide.interp.api.BytecodeSession
import dev.ide.interp.api.CodeInterpreter
import dev.ide.interp.api.InterpretConfig
import dev.ide.interp.api.InterpretException
import dev.ide.interp.api.LowerRequest
import dev.ide.interp.api.LowerResult
import dev.ide.interp.api.LoweredProgram
import dev.ide.interp.api.SandboxCategories
import dev.ide.interp.api.SourceSession
import dev.ide.jvm.PeerFactory
import dev.ide.platform.ServiceKey
import dev.ide.platform.ServiceLookup

/**
 * The engine behind [CodeInterpreter]: it owns session construction and defers lowering to the host.
 *
 * Lowering is [lowering]'s job because it needs the open project's analyzers, indexes and module graph, which
 * live in `:ide-core`. Everything else (building an interpreter, composing the sandbox with the plugin's own
 * hooks, running the VM) is here, so the pieces that know about `:interp-core` and `:jvm-interp` stay out of
 * the engine's own module.
 */
class CodeInterpreterImpl(
    private val lowering: ProgramLowering,
    /**
     * The sandbox categories the open project restricts for previews, consulted when a plugin's config names
     * none. A plugin's preview is then held to the same rules as the built-in one, which is the point: the
     * user configured those, not the plugin.
     */
    private val projectSandbox: () -> Set<String>,
    /** The application container, for the optional ports below. Empty in a test. */
    private val appServices: ServiceLookup = ServiceLookup.Empty,
) : CodeInterpreter {

    override fun lower(request: LowerRequest): LowerResult = lowering.lower(request)

    override fun openSource(program: LoweredProgram, config: InterpretConfig): SourceSession {
        val lowered = program as? LoweredKotlinProgram
            ?: throw InterpretException(
                "this program was not produced by CodeInterpreter.lower (${program.javaClass.name})"
            )
        return SourceSessionImpl(lowered, config, config.sandbox ?: projectSandbox())
    }

    override fun openBytecode(config: BytecodeConfig): BytecodeSession =
        BytecodeSessionImpl(config, appServices.getServiceOrNull(VM_PEER_FACTORY))
}

/**
 * Lowers a Kotlin declaration to a runnable program. Implemented by the host, which owns the analyzers.
 *
 * A separate port rather than a method on the impl so the session engine can be built and tested without a
 * project: a test supplies an already-lowered program and never lowers anything.
 */
fun interface ProgramLowering {
    fun lower(request: LowerRequest): LowerResult
}

/**
 * How a bytecode session realizes the real peer objects platform code holds for an interpreted instance.
 *
 * Optional, and registered by the host that needs a non-default one: on ART, defining a class from class-file
 * bytes is not possible, so the device launcher registers a factory that dexes the generated peer instead
 * (the same one the console run and the Compose preview use). Absent, sessions use the ASM factory, which is
 * correct on the desktop JVM and correct on device for the common case (a class implementing only interfaces
 * needs no generated peer at all).
 */
val VM_PEER_FACTORY = ServiceKey<PeerFactory>("platform.vmPeerFactory")

/** The most restrictive sandbox, for a caller that wants one without naming categories. */
val RESTRICT_ALL: Set<String> = SandboxCategories.ALL
