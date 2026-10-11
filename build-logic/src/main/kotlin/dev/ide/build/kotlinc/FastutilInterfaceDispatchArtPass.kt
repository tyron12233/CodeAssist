package dev.ide.build.kotlinc

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Takes IntelliJ's PSI tree builder off ART's interface dispatch for its fastutil collections, by routing each
 * `invokeinterface` on `IntSet` / `Int2ObjectMap` in [TARGETS] to a static [FastutilDirectCalls] shim
 * (`dev.ide.psi.compat`, in `:intellij-psi-host`), receiver first.
 *
 * Why: the largest native crash cluster in the field is a SIGSEGV in `artInstanceOfFromCode` under
 * `PsiBuilderImpl.bind` -> `ProductionMarker.isCollapsed`, on Android 12 / 12L only. `isCollapsed` is one
 * `invokeinterface IntSet.contains(I)Z` on an `IntOpenHashSet`, and the tombstone's next frame is
 * `AbstractIntCollection.removeAll(Collection)` at dex pc 0, which is `instanceof IntCollection` on its argument.
 * So ART dispatched `contains(int)` to `removeAll(Collection)` and handed it the marker id as an object
 * reference; the fault address is that id (0x6, 0x7, 0x8, ...). The shim does an `instanceof` on the concrete
 * class and then an `invokevirtual`, which goes through the vtable and never touches the interface method table.
 *
 * Every fastutil interface call in a target class must have a shim: an unmapped one fails the build, so an
 * IntelliJ upgrade that adds a call site is caught here and not on a device.
 */
class FastutilInterfaceDispatchArtPass : ArtPatchPass {

    override val name: String = "fastutil-interface-dispatch"

    override fun handles(classFqn: String): Boolean = classFqn in TARGETS

    override fun visitor(classFqn: String, next: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, next) {
            override fun visitMethod(
                access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?,
            ): MethodVisitor? {
                val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
                return CallRewriter(mv, "$classFqn.$name$descriptor")
            }
        }

    private class CallRewriter(next: MethodVisitor, private val where: String) : MethodVisitor(Opcodes.ASM9, next) {
        override fun visitMethodInsn(
            opcode: Int, owner: String?, name: String?, descriptor: String?, isInterface: Boolean,
        ) {
            if (opcode != Opcodes.INVOKEINTERFACE || owner == null || !owner.startsWith(FASTUTIL)) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                return
            }
            val shimName = SHIMS["$owner.$name$descriptor"]
                ?: error("$where calls $owner.$name$descriptor through invokeinterface, which has no shim in $SHIM")
            super.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM, shimName, "(L$owner;${descriptor!!.substring(1)}", false)
        }
    }

    companion object {
        val TARGETS = setOf(
            "com.intellij.lang.impl.MarkerOptionalData",
            "com.intellij.lang.impl.PsiBuilderImpl\$MyTreeStructure",
        )
        const val SHIM = "dev/ide/psi/compat/FastutilDirectCalls"
        private const val FASTUTIL = "it/unimi/dsi/fastutil/"
        private const val INT_SET = "it/unimi/dsi/fastutil/ints/IntSet"
        private const val INT2OBJECT = "it/unimi/dsi/fastutil/ints/Int2ObjectMap"

        /** `owner.name+descriptor` of the interface call -> the [SHIM] method taking the receiver first. */
        val SHIMS = mapOf(
            "$INT_SET.contains(I)Z" to "intSetContains",
            "$INT_SET.add(I)Z" to "intSetAdd",
            "$INT_SET.remove(I)Z" to "intSetRemove",
            "$INT2OBJECT.get(I)Ljava/lang/Object;" to "int2ObjectGet",
            "$INT2OBJECT.put(ILjava/lang/Object;)Ljava/lang/Object;" to "int2ObjectPut",
            "$INT2OBJECT.remove(I)Ljava/lang/Object;" to "int2ObjectRemove",
        )
    }
}
