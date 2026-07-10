package dev.ide.build.kotlinc

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.TryCatchBlockNode

/**
 * Makes the IntelliJ platform's `com.intellij.util.containers.Unsafe` initialize on ART.
 *
 * Its `<clinit>` eagerly resolves a MethodHandle for every `sun.misc.Unsafe` operation it wraps via a
 * `find(name, returnType, paramTypes...)` helper, and wraps any failure in a `java.lang.Error`. ART's
 * `sun.misc.Unsafe` provides nine of the ten (compareAndSwap*, get*Volatile, putObjectVolatile,
 * getAndAddInt, objectFieldOffset, arrayBaseOffset/IndexScale) but NOT the 5-arg
 * `copyMemory(Object, long, Object, long, long)` overload - so the whole class fails to initialize, which
 * kills `ConcurrentLongObjectHashMap.<clinit>` -> `CoreProgressManager.<clinit>` -> every
 * `KotlinCoreEnvironment` (the editor parse host AND the in-process build compiler), exactly as the device
 * spike logcat shows.
 *
 * Fix: wrap `find`'s body in a catch-all that returns `null` instead of throwing. The nine ART-supported
 * handles resolve normally (keeping the MethodHandle fast path - no reflection fallback), `copyMemory`
 * becomes a null handle, and `<clinit>` completes. A call to the wrapper's `copyMemory` on ART would NPE,
 * but nothing on the in-process compile/parse paths uses it - the same evidence the isolated AA runtime
 * relies on: its replacement shim (aa-runtime `src/aaShims`) does not implement `copyMemory` at all.
 * Desktop bytecode is untouched (this pass runs only in the Android build), and even there the rewrite
 * would be behavior-preserving: every lookup succeeds on a real JDK.
 */
class UnsafeFindTolerantPass : ArtPatchPass {

    override val name: String = "unsafe-find-tolerant"

    override fun handles(classFqn: String): Boolean = classFqn == TARGET

    override fun visitor(classFqn: String, next: ClassVisitor): ClassVisitor = FindWrappingNode(next)

    private class FindWrappingNode(private val downstream: ClassVisitor) : ClassNode(Opcodes.ASM9) {
        override fun visitEnd() {
            val find = methods.singleOrNull { it.name == "find" }
            if (find != null) {
                val start = LabelNode()
                val end = LabelNode()
                val handler = LabelNode()
                find.instructions.insertBefore(find.instructions.first, start)
                find.instructions.add(end)
                find.instructions.add(handler)
                // Handler: discard the caught Throwable, return null (an unresolvable handle, not an Error).
                find.instructions.add(InsnNode(Opcodes.POP))
                find.instructions.add(InsnNode(Opcodes.ACONST_NULL))
                find.instructions.add(InsnNode(Opcodes.ARETURN))
                find.tryCatchBlocks = (find.tryCatchBlocks ?: mutableListOf()).also {
                    it.add(TryCatchBlockNode(start, end, handler, "java/lang/Throwable"))
                }
                // AGP recomputes frames/maxs for instrumented methods; maxStack only needs to fit the handler.
                if (find.maxStack < 1) find.maxStack = 1
            }
            accept(downstream)
        }
    }

    private companion object {
        const val TARGET = "com.intellij.util.containers.Unsafe"
    }
}
