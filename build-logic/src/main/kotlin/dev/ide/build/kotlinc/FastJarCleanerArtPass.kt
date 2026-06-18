package dev.ide.build.kotlinc

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodNode

/**
 * Enables the Kotlin compiler's fast (mmap-backed) JAR file system on ART.
 *
 * The compiler reads every classpath jar — `android.jar`, the stdlib, every library — through one of two VFS
 * implementations. The fast one (`FastJarFileSystem`) `mmap`s each jar and parses its central directory once;
 * the slow fallback (`CoreJarFileSystem`) reopens the jar with `ZipFile` and inflates entries on demand. With
 * the application environment kept warm across builds (see `KotlinEnvironmentKeepAlive`), the fast FS reads
 * `android.jar` once and reuses it for every subsequent compile — the single biggest jar-I/O win on device.
 *
 * The compiler only uses the fast FS if it can *unmap* a mapped buffer, probed by
 * `FastJarFileSystemKt.prepareCleanerCallback()`: it reflects for `sun.misc.Unsafe.invokeCleaner(ByteBuffer)`
 * (JDK 9+) or `DirectByteBuffer.cleaner()` (older). **Neither exists on ART** — Android's `sun.misc.Unsafe`
 * has no `invokeCleaner` and its `DirectByteBuffer` exposes no `cleaner()` — so the probe returns `null`,
 * `createIfUnmappingPossible()` returns `null`, and the compiler logs *"Your JDK doesn't seem to support
 * mapped buffer unmapping, so the slower (old) version of JAR FS will be used"* and falls back to the slow FS
 * for every jar, every compile. (That same warning is the one `:lang-kotlin`'s parse host already silences.)
 *
 * **Strategy: make the probe return a non-null no-op callback.** A non-null callback is all
 * `createIfUnmappingPossible()` needs to construct a real `FastJarFileSystem`. We can't unmap on ART, so the
 * callback does nothing — the mapped buffers are reclaimed by ART's GC / `NativeAllocationRegistry` instead of
 * an explicit `clean()`. The compiler maps a bounded set of jars and `FastJarHandler` keeps an LRU of open
 * handles, so leaving eviction to the GC is acceptable for an in-process compiler run.
 *
 * The rewrite replaces `prepareCleanerCallback()`'s body with `return <lambda → art$noopUnmap>`, where the
 * lambda is a standard `LambdaMetafactory` `invokedynamic` (D8 desugars it into a synthetic class at dex time,
 * exactly as it does for the compiler's own Kotlin lambdas) bound to a synthetic no-op method this pass adds to
 * the same class. This pass runs only in the Android build; on desktop the unpatched compiler unmaps natively.
 */
class FastJarCleanerArtPass : ArtPatchPass {

    override val name: String = "fastjar-cleaner-noop"

    override fun handles(classFqn: String): Boolean = classFqn == TARGET

    override fun visitor(classFqn: String, next: ClassVisitor): ClassVisitor = object : ClassNode(Opcodes.ASM9) {
        override fun visitEnd() {
            // Add the synthetic no-op the lambda binds to: `static Unit art$noopUnmap(ByteBuffer) { return Unit.INSTANCE; }`.
            // Package-private (not private) so D8's desugared lambda class can invoke it without a synthetic accessor.
            methods.add(
                MethodNode(Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC, NOOP_NAME, NOOP_DESC, null, null).apply {
                    instructions = InsnList().apply {
                        add(FieldInsnNode(Opcodes.GETSTATIC, UNIT, "INSTANCE", "L$UNIT;"))
                        add(InsnNode(Opcodes.ARETURN))
                    }
                    maxStack = 1
                    maxLocals = 1 // the (ignored) ByteBuffer parameter
                },
            )

            for (method in methods) {
                if (method.name == PROBE_NAME) {
                    method.instructions = InsnList().apply {
                        // (Function1) () -> art$noopUnmap  — captures nothing; LambdaMetafactory builds the Function1.
                        add(
                            InvokeDynamicInsnNode(
                                "invoke",
                                "()L$FUNCTION1;",
                                METAFACTORY,
                                Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"), // Function1.invoke, erased
                                Handle(Opcodes.H_INVOKESTATIC, TARGET_INTERNAL, NOOP_NAME, NOOP_DESC, false),
                                Type.getMethodType(NOOP_DESC), // instantiated: (ByteBuffer) -> Unit
                            ),
                        )
                        add(InsnNode(Opcodes.ARETURN))
                    }
                    method.tryCatchBlocks = ArrayList()
                    method.localVariables = ArrayList()
                    method.maxStack = 1
                    method.maxLocals = 0 // static, no parameters
                }
            }
            accept(next)
        }
    }

    private companion object {
        const val TARGET = "org.jetbrains.kotlin.cli.jvm.compiler.jarfs.FastJarFileSystemKt"
        const val TARGET_INTERNAL = "org/jetbrains/kotlin/cli/jvm/compiler/jarfs/FastJarFileSystemKt"
        const val PROBE_NAME = "prepareCleanerCallback"
        const val NOOP_NAME = "art\$noopUnmap"
        const val NOOP_DESC = "(Ljava/nio/ByteBuffer;)Lkotlin/Unit;"
        const val UNIT = "kotlin/Unit"
        const val FUNCTION1 = "kotlin/jvm/functions/Function1"

        val METAFACTORY = Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory",
            "metafactory",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                "Ljava/lang/invoke/CallSite;",
            false,
        )
    }
}
