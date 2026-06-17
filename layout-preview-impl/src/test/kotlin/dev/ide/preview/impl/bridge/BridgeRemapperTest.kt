package dev.ide.preview.impl.bridge

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals

class BridgeRemapperTest {

    /** A hand-built `com/example/app/MyChart extends android/view/View` whose ctor calls obtainStyledAttributes. */
    private fun userView(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/app/MyChart", null, "android/view/View", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Landroid/content/Context;Landroid/util/AttributeSet;)V", null, null)
        mv.visitCode()
        // super(context)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "android/view/View", "<init>", "(Landroid/content/Context;)V", false)
        // TypedArray a = context.obtainStyledAttributes(new int[0]);
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "android/content/Context", "obtainStyledAttributes", "([I)Landroid/content/res/TypedArray;", false)
        mv.visitVarInsn(Opcodes.ASTORE, 3)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test fun `reparents view base and redirects obtainStyledAttributes`() {
        val out = BridgeRemapper().transform(userView())

        var superName: String? = null
        var obtainOpcode = -1
        var obtainOwner: String? = null
        var obtainDescriptor: String? = null

        ClassReader(out).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(v: Int, a: Int, name: String?, sig: String?, superN: String?, ifaces: Array<out String>?) {
                superName = superN
            }
            override fun visitMethod(a: Int, n: String?, d: String?, s: String?, e: Array<out String>?): MethodVisitor =
                object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(op: Int, owner: String, name: String, desc: String, itf: Boolean) {
                        if (name == "obtainStyledAttributes") {
                            obtainOpcode = op; obtainOwner = owner; obtainDescriptor = desc
                        }
                    }
                }
        }, 0)

        assertEquals("dev/ide/preview/bridge/widget/BridgeView", superName, "view base must be reparented")
        assertEquals(Opcodes.INVOKESTATIC, obtainOpcode, "obtainStyledAttributes must become a static bridge call")
        assertEquals("dev/ide/preview/bridge/Bridges", obtainOwner)
        // receiver prepended as first arg; return type remapped to BridgeTypedArray
        assertEquals("(Landroid/content/Context;[I)Ldev/ide/preview/bridge/res/BridgeTypedArray;", obtainDescriptor)
    }
}
