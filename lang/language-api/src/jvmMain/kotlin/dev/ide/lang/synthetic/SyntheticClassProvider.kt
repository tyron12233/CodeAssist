package dev.ide.lang.synthetic

import dev.ide.model.Module
import dev.ide.model.Workspace
import dev.ide.platform.ExtensionPoint

/** What a provider is asked about: one [module] of the [workspace]. Providers read the model themselves
 *  (facets, source/resource roots, dependencies) and return the synthetic classes that module should see. */
interface SyntheticClassContext {
    val module: Module
    val workspace: Workspace
}

/**
 * Contributes synthetic classes for a module (registered through [SYNTHETIC_CLASS_EP]). Return the classes
 * the [SyntheticClassContext.module] should resolve — empty if the provider doesn't apply (e.g. the Android
 * `R` provider returns nothing for a non-Android module). Called per module; must be cheap or cached by the
 * host (the JDT host caches the rendered result and refreshes it on file changes).
 */
fun interface SyntheticClassProvider {
    fun classesFor(context: SyntheticClassContext): List<SyntheticClass>
}

/** Plugins contribute synthetic ("light") classes here — e.g. android-support's `R`/`BuildConfig`. */
val SYNTHETIC_CLASS_EP = ExtensionPoint<SyntheticClassProvider>("platform.syntheticClass")
