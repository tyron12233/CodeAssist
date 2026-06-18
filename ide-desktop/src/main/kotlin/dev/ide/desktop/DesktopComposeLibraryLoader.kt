package dev.ide.desktop

import dev.ide.core.ComposePreviewLibs
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * The desktop counterpart to :ide-android's `ComposeLibraryLoader`: builds the [ClassLoader] the Compose
 * interpreter dispatches library calls through so the project's own libraries (`material-icons`, third-party
 * Compose widgets, sibling library modules) are callable in the desktop preview — where, unlike on device,
 * only the IDE's *bundled* Compose-for-Desktop is on the process classpath.
 *
 * It wraps the project's resolved library jars in a plain [URLClassLoader] whose **parent is the IDE app
 * loader** (no dexing needed on the JVM). Parent-first delegation means `androidx.compose.runtime.*`,
 * `androidx.compose.ui.*` and `androidx.compose.material3.*` resolve to the IDE's bundled Compose-for-Desktop
 * — so the `Composer` threaded in from the IDE's composition stays type-compatible, and the standard
 * composables keep dispatching against the runtime the renderer actually drives — while a library the IDE does
 * NOT bundle (e.g. `androidx.compose.material.icons.Icons`) loads from the project's jars. This is the desktop
 * application of Approach A (`docs/compose-interpreter.md`): the project's Compose version must be
 * ABI-compatible with the IDE's bundled runtime; a wild skew degrades to a render error (caught by the host).
 *
 * Returns null when there's nothing to load (no jars), so the renderer falls back to the bundled Compose —
 * unchanged behaviour for a preview built only from standard composables.
 */
object DesktopComposeLibraryLoader {

    private val cache = ConcurrentHashMap<String, ClassLoader>()

    fun loaderFor(libs: ComposePreviewLibs): ClassLoader? {
        val jars = libs.jars.filter { Files.exists(it) }
        if (jars.isEmpty()) return null
        return cache.getOrPut(libs.fingerprint) {
            URLClassLoader(jars.map { it.toUri().toURL() }.toTypedArray(), javaClass.classLoader)
        }
    }
}
