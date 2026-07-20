package dev.ide.build.kotlinc

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

/**
 * Relocates every reference to `java.lang.ClassValue` in the IntelliJ-platform classes that use it, to the
 * ART-safe shim [dev.ide.lang.jdt.compat.ClassValue].
 *
 * `java.lang.ClassValue` (a JDK-7 type) is absent from ART. ART eager-links class references at load, so a
 * class that touches the absent type — as a superclass, a field type, a `new`, or an `invokevirtual
 * ClassValue.get` — throws `NoClassDefFoundError: java.lang.ClassValue` the moment it is loaded/verified.
 *
 * The reported crash is `com.intellij.util.messages.impl.MethodHandleCache` (a `static final ClassValue CACHE`
 * + inner `$ConcurrentMapClassValue extends ClassValue`): the IntelliJ MessageBus resolves EVERY listener
 * method handle through it, so the first PSI-change event on device crashes the app — e.g.
 * `PsiManagerImpl.dropResolveCaches` fired from `JavaEnvironment.dropCaches` during an editor document update.
 * (The Kotlin backend separately AVOIDS the message bus in `KotlinPsiMutation`; the Java/IntelliJ-PSI backend
 * does not, so it hit this.) The bundled unshaded platform jar has FOUR more `ClassValue` users, patched here
 * proactively so they don't become the next crash on their own code path — most notably `PropertyCollector` /
 * `XmlSerializer…`, IntelliJ's XML-bean deserializer, which extension-point/component descriptor loading reaches.
 *
 * `ClassValue` is a `java.*` type, so it cannot be shipped under its own name on ART (like `Runtime$Version` /
 * `StackWalker`). A [ClassRemapper] rewrites the type UNIFORMLY across the superclass, field types + generic
 * signatures, method descriptors and every instruction, so no residual `java/lang/ClassValue` reference remains.
 * The shim mirrors `get`/`computeValue`/`remove` with a `ConcurrentHashMap`-backed memoizer. Like the other
 * passes this rides the `dev.ide.kotlinc-art` AGP instrumentation (scope = ALL), which reaches the merged
 * IntelliJ-platform jar; desktop is untouched (it keeps the real `java.lang.ClassValue`).
 *
 * The [TARGETS] set is the current jar's `ClassValue` users (found by scanning it). It is pinned to the pinned
 * compiler-jar version; a jar bump may surface a new one, caught by the on-device spike like the other passes.
 */
class ClassValueArtPass : ArtPatchPass {

    override val name: String = "class-value-shim"

    override fun handles(classFqn: String): Boolean =
        TARGETS.any { classFqn == it || classFqn.startsWith("$it\$") }

    override fun visitor(classFqn: String, next: ClassVisitor): ClassVisitor =
        ClassRemapper(next, ClassValueRemapper)

    private object ClassValueRemapper : Remapper() {
        override fun map(internalName: String): String =
            if (internalName == CLASS_VALUE) SHIM else internalName
    }

    private companion object {
        /** Top-level classes in the bundled unshaded platform jar that reference `java.lang.ClassValue` (inner
         *  classes matched via the `$` prefix). */
        val TARGETS = setOf(
            "com.intellij.util.messages.impl.MethodHandleCache",        // the reported crash (MessageBus)
            "com.intellij.util.ClearableClassValue",                    // a clearable per-class cache
            "com.intellij.util.xmlb.XmlSerializerPropertyCollectorListClassValue",
            "com.intellij.serialization.PropertyCollector",             // XmlSerializer bean deserialization
            "com.intellij.openapi.util.DefaultJDOMExternalizer",        // legacy JDOM settings serialization
        )
        const val CLASS_VALUE = "java/lang/ClassValue"
        const val SHIM = "dev/ide/lang/jdt/compat/ClassValue"
    }
}
