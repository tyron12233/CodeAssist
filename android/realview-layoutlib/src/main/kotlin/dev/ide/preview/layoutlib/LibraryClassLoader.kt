package dev.ide.preview.layoutlib

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.io.File
import java.net.URLClassLoader

/**
 * Loads the views a layout names, and what they need, from the project's jars and class folders.
 *
 * Two kinds of class do not come from those files as they are. `R` classes are made by [ResourceIds]. And any
 * class that names `android.os.Build` is rewritten as it loads to name `android.os._Original_Build`: layoutlib
 * ships the class under that name (it stands in its own at render time), so code compiled against the real one
 * would otherwise fail to link. Android Studio does the same.
 *
 * The project's own files are asked before the parent, for everything but the platform's classes. The IDE
 * this runs in has libraries of its own under the same names (its Compose is the desktop one, with a
 * `SavedStateRegistryController` whose methods take other types), and a project's Compose linked against
 * the IDE's copy of what it calls fails with a `NoSuchMethodError`.
 */
internal class LibraryClassLoader(
    classpath: List<File>,
    parent: ClassLoader,
    private val ids: ResourceIds,
) : URLClassLoader(classpath.filter { it.exists() }.map { it.toURI().toURL() }.toTypedArray(), parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (PLATFORM.any { name.startsWith(it) }) return super.loadClass(name, resolve)
        synchronized(getClassLoadingLock(name)) {
            val loaded = findLoadedClass(name) ?: try {
                findClass(name)
            } catch (notHere: ClassNotFoundException) {
                return super.loadClass(name, resolve)
            }
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }

    override fun findClass(name: String): Class<*> {
        ids.classBytes(name)?.let { return defineClass(name, it, 0, it.size) }
        val url = findResource(name.replace('.', '/') + ".class") ?: throw ClassNotFoundException(name)
        var bytes = url.openStream().use { it.readBytes() }
        if (mentionsBuild(bytes)) {
            val writer = ClassWriter(0)
            ClassReader(bytes).accept(ClassRemapper(writer, BUILD), 0)
            bytes = writer.toByteArray()
        }
        return defineClass(name, bytes, 0, bytes.size)
    }

    private fun mentionsBuild(bytes: ByteArray): Boolean {
        outer@ for (i in 0..bytes.size - NEEDLE.size) {
            for (j in NEEDLE.indices) if (bytes[i + j] != NEEDLE[j]) continue@outer
            return true
        }
        return false
    }

    private companion object {
        /** What only the parent has, or must be the parent's for layoutlib and the project to mean the same class. */
        val PLATFORM = listOf(
            "java.", "javax.", "jdk.", "sun.", "android.", "com.android.", "dalvik.", "libcore.", "org.xmlpull.", "org.kxml2.",
            "org.w3c.", "org.xml.",
        )
        const val BUILD_NAME = "android/os/Build"
        val NEEDLE = BUILD_NAME.toByteArray(Charsets.ISO_8859_1)
        val BUILD = object : Remapper() {
            override fun map(internalName: String): String =
                if (internalName == BUILD_NAME || internalName.startsWith("$BUILD_NAME$")) {
                    "android/os/_Original_Build" + internalName.substring(BUILD_NAME.length)
                } else internalName
        }
    }
}
