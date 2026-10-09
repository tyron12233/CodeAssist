package dev.ide.interp.compose

import dev.ide.jvm.StaticRedirect
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes a call into an Android-only Kotlin file facade to the host's own `actual` of the same declaration, for
 * library bytecode compiled against Android Compose and run against Compose for Desktop. An `expect` top-level
 * function or property compiles to `<File>_androidKt` on Android and to a facade with another suffix on skiko
 * (`WindowInsets.skiko.kt` is `@file:JvmName("WindowInsets_notMobileKt")`), so the desktop host never has the
 * Android facade and the VM would interpret its body instead: Android platform code with no View behind it.
 *
 * The motivating case is the project's `material3-android` `Scaffold`, whose default content insets read
 * `WindowInsets.systemBars`. Interpreted, that is `WindowInsetsHolder.current()` → `LocalView.current` (absent on
 * desktop) → `view.getParent()` on null, and the whole preview fails. The skiko `actual` returns the desktop's
 * (zero) insets, the same as Compose for Desktop's own Material3.
 *
 * Only an exact name + descriptor match on a host facade is taken, so a declaration the host doesn't have keeps
 * interpreting. On Android the facade is host-loadable and this never applies.
 */
internal class PlatformFacadeRedirect(
    private val loader: ClassLoader,
    /** Whether the host itself can load the binary name (then its own facade serves and nothing is redirected). */
    private val hostLoadable: (String) -> Boolean,
    /** Owners never redirected: namespaces interpreted from the project even when the host bundles them, whose
     *  host types would not be the interpreted ones the caller expects. */
    private val keepInterpreted: (String) -> Boolean,
) : StaticRedirect {

    private val resolved = ConcurrentHashMap<String, String>()

    override fun hostOwner(owner: String, name: String, descriptor: String): String? {
        if (!owner.endsWith(ANDROID_FACADE)) return null
        val hit = resolved.computeIfAbsent("$owner.$name$descriptor") { counterpart(owner, name, descriptor) ?: NONE }
        return hit.takeIf { it != NONE }
    }

    private fun counterpart(owner: String, name: String, descriptor: String): String? {
        val binary = owner.replace('/', '.')
        if (keepInterpreted(binary) || hostLoadable(binary)) return null
        val base = binary.removeSuffix(ANDROID_FACADE)
        for (suffix in HOST_FACADES) {
            val cls = runCatching { Class.forName("${base}_${suffix}Kt", false, loader) }.getOrNull() ?: continue
            val declares = cls.declaredMethods.any {
                Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && it.name == name &&
                    methodDescriptor(it) == descriptor
            }
            if (declares) return cls.name.replace('.', '/')
        }
        return null
    }

    private fun methodDescriptor(m: java.lang.reflect.Method): String =
        m.parameterTypes.joinToString("", "(", ")") { typeDescriptor(it) } + typeDescriptor(m.returnType)

    private fun typeDescriptor(c: Class<*>): String = when {
        c.isArray -> c.name.replace('.', '/')
        c == Void.TYPE -> "V"
        c == java.lang.Boolean.TYPE -> "Z"
        c == java.lang.Byte.TYPE -> "B"
        c == java.lang.Character.TYPE -> "C"
        c == java.lang.Short.TYPE -> "S"
        c == java.lang.Integer.TYPE -> "I"
        c == java.lang.Long.TYPE -> "J"
        c == java.lang.Float.TYPE -> "F"
        c == java.lang.Double.TYPE -> "D"
        else -> "L${c.name.replace('.', '/')};"
    }

    private companion object {
        const val ANDROID_FACADE = "_androidKt"
        const val NONE = ""

        /** The facade suffixes Compose's non-Android source sets compile to, most specific first. */
        val HOST_FACADES = listOf("notMobile", "skiko", "desktop", "jvm", "nonAndroid")
    }
}
