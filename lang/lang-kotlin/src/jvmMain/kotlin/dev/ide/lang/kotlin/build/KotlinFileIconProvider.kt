package dev.ide.lang.kotlin.build

import dev.ide.model.FileIconProvider
import dev.ide.model.IconTarget

/**
 * Marks a Kotlin Multiplatform module in the project tree (`module.kmp`, the folder with a Kotlin-purple module
 * mark). Above the Android provider on purpose: a KMP `shared` module imported for its Android target is an
 * Android library to the build, but what the user needs to see is that it is the shared code.
 */
object KotlinFileIconProvider : FileIconProvider {
    override val priority: Int get() = 150

    override fun iconFor(target: IconTarget): String? = when (target) {
        is IconTarget.ModuleNode ->
            if (target.module.facets.get(KotlinFacet.KEY)?.multiplatform == true) "module.kmp" else null
        else -> null
    }
}
