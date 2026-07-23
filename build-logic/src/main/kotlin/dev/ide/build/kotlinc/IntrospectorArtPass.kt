package dev.ide.build.kotlinc

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Relocates the two `java.beans.Introspector` statics the bundled IntelliJ platform calls —
 * `decapitalize(String)` and `flushCaches()` — to the ART-safe shim [dev.ide.lang.jdt.compat.IntrospectorCompat].
 *
 * `java.beans.Introspector` is absent from ART (Android ships only the `PropertyChange*` slice of `java.beans`,
 * not the introspection API). ART eager-links class references, so the moment a platform class executes an
 * `INVOKESTATIC java/beans/Introspector.*` instruction it throws `NoClassDefFoundError: java.beans.Introspector`.
 * Confirmed on device (`FindSuperMethodsArtProbeTest`): `PsiMethod.findSuperMethods()` / `findDeepestSuperMethods()`
 * hit it and threw — and because the caller wraps them in a catch-all that yields an empty list, the editor's
 * `@Override` check reported valid library overrides (`onCreate`, an anonymous `View.OnClickListener.onClick`) as
 * "does not override or implement". Every other hierarchy API (`getAllMethods`, `visibleSignatures`,
 * `hierarchicalMethodSignature`) works — only the Introspector-touching paths broke.
 *
 * Only two methods are ever called, so the shim is trivial; a `java.*` type can't be shipped under its own name
 * on ART, so this pass rewrites the call sites instead (like [EcjInputStreamArtPass] for `InputStream.readAllBytes`).
 * Both are already `static` with the shim's exact signatures, so only the owner changes — the stack effect is
 * identical. Fixing the class (not the caller) means `findSuperMethods` and every other Introspector-dependent
 * path across `:lang-java` AND `:lang-kotlin` (getter/setter detection via `PropertyUtilBase`, the `*Search`
 * query infra via `ExtensibleQueryFactory`) work on device, not just this one check.
 *
 * Scoped to the four confirmed platform classes (via [FindSuperMethodsArtProbeTest] / a jar scan) so no other
 * class is routed through the visitor; `endsWith` matches both the unshaded (`com.intellij.*`) and the
 * embeddable (`org.jetbrains.kotlin.com.intellij.*`) forms. `com.thoughtworks.xstream`'s Introspector user is
 * deliberately NOT handled — it needs full bean introspection (`getBeanInfo`), which this shim doesn't provide,
 * and it isn't on the editor path.
 */
class IntrospectorArtPass : ArtPatchPass {

    override val name: String = "beans-introspector"

    override fun handles(classFqn: String): Boolean = TARGETS.any { classFqn.endsWith(it) }

    override fun visitor(classFqn: String, next: ClassVisitor): ClassVisitor = Rewriter(next)

    private class Rewriter(next: ClassVisitor) : ClassVisitor(Opcodes.ASM9, next) {
        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor {
            val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
            return object : MethodVisitor(Opcodes.ASM9, mv) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    methodName: String,
                    methodDescriptor: String,
                    isInterface: Boolean,
                ) {
                    if (opcode == Opcodes.INVOKESTATIC && owner == INTROSPECTOR &&
                        (methodName == "decapitalize" || methodName == "flushCaches")
                    ) {
                        // Same name + descriptor, only the owner moves to the shim (both are static).
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM, methodName, methodDescriptor, false)
                        return
                    }
                    super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface)
                }
            }
        }
    }

    private companion object {
        const val INTROSPECTOR = "java/beans/Introspector"
        const val SHIM = "dev/ide/lang/jdt/compat/IntrospectorCompat"

        /** The confirmed platform classes that call `Introspector.decapitalize`/`flushCaches`. */
        val TARGETS = listOf(
            "com.intellij.openapi.util.text.StringUtil",       // decapitalize
            "com.intellij.psi.util.PropertyUtilBase",          // decapitalize
            "com.intellij.psi.search.searches.ExtensibleQueryFactory", // decapitalize
            "com.intellij.util.ref.GCUtil",                    // flushCaches
        )
    }
}
