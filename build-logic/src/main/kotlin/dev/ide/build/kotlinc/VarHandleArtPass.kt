package dev.ide.build.kotlinc

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Relocates the `java.lang.invoke.VarHandle` usage of the bundled IntelliJ-platform concurrency classes to the
 * ART-safe shim [dev.ide.lang.jdt.compat.VarHandleCompat].
 *
 * `VarHandle`'s access-mode methods (`compareAndSet`/`getVolatile`/`setVolatile`) are `@PolymorphicSignature`;
 * this device's ART verifier rejects the polymorphic call sites (`VerifyError: expected Object[], got Object`),
 * so every class that touches a `VarHandle` fails to load. That is the reported `AtomicFieldUpdater` crash
 * (hit via `XmlDocumentImpl.<clinit>`), and it also affects IntelliJ's `ConcurrentHashMap` /
 * `ConcurrentIntObjectHashMap` / `ConcurrentLongObjectHashMap` forks and `ConcurrentBitSetImpl` — used
 * pervasively, so they would crash next. (It never surfaced on the API-37 emulator, whose verifier is lenient.)
 *
 * `VarHandle` is a `java.*` type and its polymorphic calls can't be made to verify while keeping it, so this
 * rewrites, in those classes only:
 *  - each `VarHandle` field type -> [VarHandleCompat];
 *  - `MethodHandles.lookup()` / `privateLookupIn(...)` -> shim no-ops (return null), and
 *    `Lookup.findVarHandle(owner, name, type)` -> `VarHandleCompat.forField(...)` /
 *    `MethodHandles.arrayElementVarHandle(cls)` -> `VarHandleCompat.forArray(cls)` (so no `MethodHandles`
 *    executes on ART either);
 *  - each access-mode call site -> the matching [VarHandleCompat] instance method, with reference coordinate/
 *    value types WIDENED to `Object`/`Object[]` to hit the shim's generic shapes (array covariance + reference
 *    subtyping keep the stack values assignable), plus a `checkcast` after an object-array `getVolatile` to
 *    restore its declared element type.
 *
 * Only 3 access modes are used and the field VarHandles are primitive/erased-Object, so the shim needs a small
 * fixed method set. The shim maps each op to the classic `sun.misc.Unsafe` operation (same lock-free semantics
 * as the `VarHandle` form / JDK-8's `ConcurrentHashMap`). Like the other passes this rides the
 * `dev.ide.kotlinc-art` AGP instrumentation (scope = ALL); desktop keeps the real `VarHandle`.
 */
class VarHandleArtPass : ArtPatchPass {

    override val name: String = "var-handle-shim"

    override fun handles(classFqn: String): Boolean =
        TARGETS.any { classFqn == it || classFqn.startsWith("$it\$") }

    override fun visitor(classFqn: String, next: ClassVisitor): ClassVisitor = Rewriter(next)

    private class Rewriter(next: ClassVisitor) : ClassVisitor(Opcodes.ASM9, next) {
        override fun visitField(access: Int, name: String?, descriptor: String?, signature: String?, value: Any?) =
            super.visitField(access, name, descriptor?.let(::relocate), signature?.let(::relocate), value)

        override fun visitMethod(
            access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?,
        ): MethodVisitor = MethodRewriter(super.visitMethod(access, name, descriptor, signature, exceptions))
    }

    private class MethodRewriter(next: MethodVisitor) : MethodVisitor(Opcodes.ASM9, next) {
        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) =
            super.visitFieldInsn(opcode, owner, name, relocate(descriptor))

        override fun visitTypeInsn(opcode: Int, type: String) =
            super.visitTypeInsn(opcode, if (type == VAR_HANDLE) SHIM else type)

        override fun visitLocalVariable(name: String?, descriptor: String?, signature: String?, start: org.objectweb.asm.Label?, end: org.objectweb.asm.Label?, index: Int) =
            super.visitLocalVariable(name, descriptor?.let(::relocate), signature?.let(::relocate), start, end, index)

        override fun visitMethodInsn(opcode: Int, owner: String, mName: String, desc: String, itf: Boolean) {
            when {
                owner == METHOD_HANDLES && mName == "lookup" ->
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM, "lookup", desc, false)
                owner == METHOD_HANDLES && mName == "privateLookupIn" ->
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM, "privateLookupIn", desc, false)
                owner == METHOD_HANDLES && mName == "arrayElementVarHandle" ->
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM, "forArray", relocate(desc), false)
                owner == LOOKUP && mName == "findVarHandle" -> {
                    // invokevirtual Lookup.findVarHandle(Class,String,Class):VarHandle
                    //   -> invokestatic VarHandleCompat.forField(Lookup,Class,String,Class):VarHandleCompat
                    // (the Lookup receiver becomes the leading argument; net stack effect is identical).
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM, "forField", "(L$LOOKUP;${desc.substring(1).let(::relocate)}", false)
                }
                owner == VAR_HANDLE && mName in ACCESS_MODES -> {
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SHIM, mName, widen(desc), false)
                    if (mName == "getVolatile") {
                        val ret = Type.getReturnType(desc)
                        if (ret.sort == Type.OBJECT && ret.internalName != OBJECT.internalName)
                            super.visitTypeInsn(Opcodes.CHECKCAST, ret.internalName)
                    }
                }
                else -> super.visitMethodInsn(opcode, owner, mName, desc, itf)
            }
        }
    }

    private companion object {
        val TARGETS = setOf(
            "com.intellij.util.concurrency.AtomicFieldUpdater",   // the reported crash (XmlDocumentImpl.<clinit>)
            "com.intellij.util.containers.ConcurrentBitSetImpl",
            "com.intellij.concurrency.ConcurrentHashMap",
            "com.intellij.concurrency.ConcurrentIntObjectHashMap",
            "com.intellij.concurrency.ConcurrentLongObjectHashMap",
        )
        val ACCESS_MODES = setOf("compareAndSet", "getVolatile", "setVolatile")
        const val VAR_HANDLE = "java/lang/invoke/VarHandle"
        const val METHOD_HANDLES = "java/lang/invoke/MethodHandles"
        const val LOOKUP = "java/lang/invoke/MethodHandles\$Lookup"
        const val SHIM = "dev/ide/lang/jdt/compat/VarHandleCompat"

        val OBJECT: Type = Type.getObjectType("java/lang/Object")
        val OBJECT_ARRAY: Type = Type.getType("[Ljava/lang/Object;")

        /** Replace a `Ljava/lang/invoke/VarHandle;` occurrence in a descriptor/signature with the shim type. */
        fun relocate(descriptor: String): String = descriptor.replace("L$VAR_HANDLE;", "L$SHIM;")

        /** Widen every non-array object reference (and 1-D object array) in [desc] to `Object`/`Object[]`;
         *  primitives and primitive arrays are kept. Matches an access-mode call site to a shim method shape. */
        fun widen(desc: String): String =
            Type.getMethodDescriptor(widenType(Type.getReturnType(desc)), *Type.getArgumentTypes(desc).map(::widenType).toTypedArray())

        fun widenType(t: Type): Type = when {
            t.sort == Type.OBJECT -> OBJECT
            t.sort == Type.ARRAY && t.dimensions == 1 && t.elementType.sort == Type.OBJECT -> OBJECT_ARRAY
            else -> t
        }
    }
}
