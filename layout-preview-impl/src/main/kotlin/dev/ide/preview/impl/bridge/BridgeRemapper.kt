package dev.ide.preview.impl.bridge

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

/**
 * The curated build-time type map (§4): the framework view bases the user subclasses, redirected to the
 * CodeAssist owned bases, plus `TypedArray` → `BridgeTypedArray`. Grown alongside the renderer registry. The
 * bridge classes themselves live in the IDE shells (where `android.jar` is on the classpath) — this map only
 * needs the *names*, so the remapper is pure-jvm and android-free.
 */
object BridgeTypeMap {
    const val PKG = "dev/ide/preview/bridge"
    const val BRIDGES = "$PKG/Bridges"
    const val BRIDGE_TYPED_ARRAY = "$PKG/res/BridgeTypedArray"

    val TYPES: Map<String, String> = mapOf(
        "android/view/View" to "$PKG/widget/BridgeView",
        "android/view/ViewGroup" to "$PKG/widget/BridgeViewGroup",
        "android/widget/TextView" to "$PKG/widget/BridgeTextView",
        "android/widget/Button" to "$PKG/widget/BridgeTextView",
        "android/widget/ImageView" to "$PKG/widget/BridgeImageView",
        "android/widget/LinearLayout" to "$PKG/widget/BridgeLinearLayout",
        "android/widget/FrameLayout" to "$PKG/widget/BridgeFrameLayout",
        "android/content/res/TypedArray" to BRIDGE_TYPED_ARRAY,
    )
}

/**
 * Rewrites a single user `.class` so it links against the owned render runtime instead of the framework
 * (§4). Two transforms compose:
 *
 *  - **(a/b) type remap** — a [ClassRemapper] over [BridgeTypeMap] reparents the view base (`extends View` →
 *    `extends BridgeView`) and remaps every `TypedArray` reference (locals, fields, descriptors) to
 *    `BridgeTypedArray`, so `a.getColor(...)` lands on our class.
 *  - **(c) `obtainStyledAttributes` redirect** — the `INVOKEVIRTUAL ctx.obtainStyledAttributes(...)` becomes
 *    `INVOKESTATIC Bridges.obtainStyledAttributes(ctx, ...)`. Receiver-as-first-arg keeps the stack effect
 *    identical, so no frame recomputation is needed.
 *
 * Only user classes are fed through this; framework classes are never touched (they can't be — they're on
 * the boot classpath, §3).
 */
class BridgeRemapper {

    private val remapper = object : Remapper() {
        override fun map(internalName: String): String = BridgeTypeMap.TYPES[internalName] ?: internalName
    }

    fun transform(classBytes: ByteArray): ByteArray {
        val reader = ClassReader(classBytes)
        val writer = ClassWriter(0)
        val redirect = ObtainStyledAttributesRedirect(writer)
        // ClassRemapper runs first so the redirect visitor sees descriptors already remapped to BridgeTypedArray.
        reader.accept(ClassRemapper(redirect, remapper), 0)
        return writer.toByteArray()
    }

    private class ObtainStyledAttributesRedirect(next: ClassVisitor) : ClassVisitor(Opcodes.ASM9, next) {
        override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor {
            val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
            return object : MethodVisitor(Opcodes.ASM9, mv) {
                override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                    if (opcode == Opcodes.INVOKEVIRTUAL && name == "obtainStyledAttributes" &&
                        descriptor.endsWith(")L${BridgeTypeMap.BRIDGE_TYPED_ARRAY};")
                    ) {
                        // Prepend the receiver type as the first static argument; return type is already remapped.
                        val newDescriptor = "(L$owner;" + descriptor.substring(1)
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, BridgeTypeMap.BRIDGES, name, newDescriptor, false)
                    } else {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    }
                }
            }
        }
    }
}
