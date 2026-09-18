package dev.ide.lang.kotlin.compile

import dev.ide.build.KotlinCompilerPlugin

/**
 * The built-in Kotlin compiler plugins, applied unless a host overrides the list (the default for direct and
 * test wiring). The SPI itself is [KotlinCompilerPlugin] in build-api, alongside the rest of the build
 * contracts, so a plugin can contribute one without depending on this module.
 */
val BUILTIN_KOTLIN_COMPILER_PLUGINS: List<KotlinCompilerPlugin> =
    listOf(ComposeCompilerPlugin, SerializationCompilerPlugin, ParcelizeCompilerPlugin)
