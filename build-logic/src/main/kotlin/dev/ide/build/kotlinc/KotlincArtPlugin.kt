package dev.ide.build.kotlinc

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Applies the Kotlin-compiler-on-ART bytecode rewrites ([KotlincArtPatchFactory] / [ArtPatchPasses]) to an
 * Android application module. Wires `variant.instrumentation.transformClassesWith(... scope = ALL)` so the
 * rewrites reach the bundled `kotlin-compiler-embeddable` classes (and, being the same dexed classes, the
 * editor parse-host) during dexing.
 *
 * Apply with `id("dev.ide.kotlinc-art")`. Plugin-application order is irrelevant: it hooks
 * `com.android.application` lazily, so it configures whenever AGP is present.
 */
class KotlincArtPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.android.application") {
            val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
            components.onVariants { variant ->
                variant.instrumentation.transformClassesWith(
                    KotlincArtPatchFactory::class.java,
                    InstrumentationScope.ALL,
                ) { /* InstrumentationParameters.None — no parameters to configure */ }
                // Some passes will alter method bodies (and therefore stack-map frames); have AGP recompute
                // frames for the methods we touch. AGP supplies the runtime classpath for common-superclass
                // resolution, so we don't have to assemble one ourselves (a win over a standalone jar
                // transform). Untouched methods/classes are unaffected.
                variant.instrumentation.setAsmFramesComputationMode(
                    FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS,
                )
            }
        }
    }
}
