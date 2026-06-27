package dev.ide.build

import dev.ide.platform.ExtensionPoint
import java.nio.file.Path

/**
 * A build-time source generator: code that runs **before** compilation and emits new Kotlin/Java source
 * (the kind KSP processors like Room, or a ViewBinding emitter, produce). It writes into
 * [SourceGenRequest.outputDir], which the build wires as a `ContentRole.GENERATED` source root, so the
 * generated files are compiled and indexed exactly like hand-written ones.
 *
 * This is the seam KSP plugs into: a KSP runner is one `SourceGenerator` that loads its `SymbolProcessor`s
 * and emits their output. Generators are contributed through [SOURCE_GENERATOR_EP]; the build runs every
 * one that [appliesTo] a module, into that module's generated root, ahead of `compileKotlin`/`compileJava`.
 */
interface SourceGenerator {
    /** Stable id (used for the generated sub-dir and logging), e.g. `"ksp"`. */
    val id: String

    /** True when this generator should run for [request] (e.g. its processor is on the classpath). */
    fun appliesTo(request: SourceGenRequest): Boolean

    /** Run the generator, writing generated sources under [SourceGenRequest.outputDir]. */
    fun generate(request: SourceGenRequest): SourceGenResult
}

/**
 * The inputs a [SourceGenerator] runs against. Plain paths + names, no model types, so the SPI stays in
 * build-api. [outputDir] is created by the task before [SourceGenerator.generate] is called.
 */
class SourceGenRequest(
    val moduleName: String,
    /** The module's hand-written `.kt` sources (the `ContentRole.SOURCE` roots; never the generated root). */
    val kotlinSources: List<Path>,
    /** The module's hand-written `.java` sources. */
    val javaSources: List<Path>,
    /** The compile classpath (dependency outputs + library jars + boot), for symbol resolution. */
    val classpath: List<Path>,
    /** The `ContentRole.GENERATED` source root to emit into. */
    val outputDir: Path,
)

class SourceGenResult(val success: Boolean, val messages: List<String> = emptyList()) {
    companion object { val OK = SourceGenResult(true) }
}

/** Plugins contribute build-time source generators here; the build runs the applicable ones per module. */
val SOURCE_GENERATOR_EP = ExtensionPoint<SourceGenerator>("platform.sourceGenerator")
