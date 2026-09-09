package dev.ide.android.support.tools

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * The `@AndroidEntryPoint` superclass rewrite: the half of Hilt that is NOT the annotation processor.
 *
 * Hilt splits its work in two. The processor generates, for every `@AndroidEntryPoint` (or `@HiltAndroidApp`)
 * class `com.example.MainActivity`, a sibling `com.example.Hilt_MainActivity` that extends the class's
 * declared base (`ComponentActivity`, `Fragment`, `Application`, …) and performs the member injection. The
 * *Gradle plugin* then rewrites the annotated class's bytecode to extend that generated class, so
 * `MainActivity extends ComponentActivity` becomes `MainActivity extends Hilt_MainActivity`. Neither half
 * works alone: without the rewrite the generated class is dead code, nothing is ever injected, and the app
 * dies at first `@Inject` access with an `UninitializedPropertyAccessException`.
 *
 * There is no Gradle plugin in this build system, so the IDE does the rewrite itself, on the module's own
 * compiled classes, before they are dexed or jarred. It is also what lets the IDE pass the processor
 * `dagger.hilt.android.internal.disableAndroidSuperclassValidation=true` (see
 * `dev.ide.ksp.KspProcessorCatalog`). That option is a promise that this rewrite happens; setting it
 * without doing the rewrite trades a build error for a runtime crash.
 *
 * The rewrite is deliberately the same one Hilt's plugin performs, and nothing more:
 *  - the class's superclass becomes the generated `Hilt_`-prefixed sibling, and
 *  - every `INVOKESPECIAL` whose owner was the old superclass (the `super(…)` constructor call and any
 *    `super.onCreate(…)`-style call) is re-owned to it.
 *
 * It is idempotent: a class already extending its `Hilt_` sibling (what a project written against the
 * documented no-plugin setup, `@AndroidEntryPoint(Base::class)` plus `: Hilt_MainActivity()`, compiles
 * to) computes the same superclass it already has and passes through unchanged.
 */
object HiltEntryPoints {

    /** Bumped whenever the rewrite's output changes, so consumers can invalidate classes they already emitted. */
    const val VERSION = "1"

    private const val ANDROID_ENTRY_POINT = "Ldagger/hilt/android/AndroidEntryPoint;"
    private const val HILT_ANDROID_APP = "Ldagger/hilt/android/HiltAndroidApp;"
    private val ENTRY_POINT_ANNOTATIONS = setOf(ANDROID_ENTRY_POINT, HILT_ANDROID_APP)

    /** Cheap pre-gate: both annotation descriptors share this prefix, so a class whose constant pool doesn't
     *  carry it cannot be an entry point and never needs parsing. Most of a module's classes stop here. */
    private val PACKAGE_PROBE = "dagger/hilt/android/".toByteArray(Charsets.UTF_8)

    /**
     * The generated base class the entry point in [bytes] must be rewritten to extend, or null when the class
     * carries no `@AndroidEntryPoint`/`@HiltAndroidApp` (or can't be parsed, in which case it is passed through
     * rather than dropped, so a format this ASM cannot read can still ship).
     *
     * Callers should confirm that base was actually compiled before rewriting: if the processor didn't run,
     * pointing the class at a type that doesn't exist trades a missing-injection bug for a
     * `NoClassDefFoundError` at launch.
     */
    fun entryPointBase(bytes: ByteArray): String? {
        if (!containsBytes(bytes, PACKAGE_PROBE) || !isEntryPoint(bytes)) return null
        return runCatching { generatedBaseClass(ClassReader(bytes).className) }.getOrNull()
    }

    /**
     * The rewritten form of the class in [bytes], or null when it is not an entry point (or is unparseable)
     * and must be packaged byte-for-byte.
     */
    fun rewriteSuperclass(bytes: ByteArray): ByteArray? {
        if (entryPointBase(bytes) == null) return null
        return runCatching {
            // No COMPUTE_MAXS/COMPUTE_FRAMES: the rewrite changes only the superclass name and the owner of
            // existing INVOKESPECIALs, so no instruction is added or removed, stack depth is unchanged, and
            // the existing stack-map frames stay valid (they name the *values'* types, which don't move).
            // COMPUTE_FRAMES would also need to load the Android class hierarchy, which isn't on this JVM.
            val writer = ClassWriter(0)
            ClassReader(bytes).accept(SuperclassRewriter(writer), 0)
            writer.toByteArray()
        }.getOrNull()
    }

    /**
     * The generated Hilt base class for the entry point [internalName] (`com/example/MainActivity` →
     * `com/example/Hilt_MainActivity`). A nested entry point is generated as a TOP-LEVEL class in the same
     * package with its enclosing names flattened (`com/example/Outer$Inner` → `com/example/Hilt_Outer_Inner`),
     * matching the processor's `Hilt_` + enclosed-class-name convention.
     */
    fun generatedBaseClass(internalName: String): String {
        val slash = internalName.lastIndexOf('/')
        val pkg = if (slash < 0) "" else internalName.substring(0, slash + 1)
        return pkg + "Hilt_" + internalName.substring(slash + 1).replace('$', '_')
    }

    /** True when the class carries a class-level `@AndroidEntryPoint`/`@HiltAndroidApp`. Both are
     *  `CLASS`-retention, so they arrive as *invisible* annotations, hence no `visible` filter here. */
    private fun isEntryPoint(bytes: ByteArray): Boolean {
        var found = false
        runCatching {
            ClassReader(bytes).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                        if (descriptor in ENTRY_POINT_ANNOTATIONS) found = true
                        return null
                    }
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
        }
        return found
    }

    private class SuperclassRewriter(next: ClassVisitor) : ClassVisitor(Opcodes.ASM9, next) {
        private var oldSuperclass: String? = null
        private var newSuperclass: String? = null

        override fun visit(
            version: Int, access: Int, name: String, signature: String?, superName: String?,
            interfaces: Array<out String>?,
        ) {
            oldSuperclass = superName
            newSuperclass = generatedBaseClass(name)
            // `signature` (the generic superclass, when the base is parameterized) is passed through as Hilt's
            // own plugin does. It is reflective metadata only (never verified, and the generated base extends
            // the same parameterized type), so rewriting it would buy nothing and dropping it would lose the
            // class's own type parameters.
            super.visit(version, access, name, signature, newSuperclass ?: superName, interfaces)
        }

        override fun visitMethod(
            access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?,
        ): MethodVisitor? {
            val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
            val old = oldSuperclass ?: return mv
            val new = newSuperclass ?: return mv
            if (old == new) return mv
            return object : MethodVisitor(Opcodes.ASM9, mv) {
                override fun visitMethodInsn(
                    opcode: Int, owner: String?, mName: String?, mDesc: String?, isInterface: Boolean,
                ) {
                    // Re-own the `super(…)` constructor call and every `super.method(…)` call: the verifier
                    // requires an INVOKESPECIAL owner to be the *declared* superclass (or the class itself),
                    // and that is now the generated one.
                    val rewritten = if (opcode == Opcodes.INVOKESPECIAL && owner == old) new else owner
                    super.visitMethodInsn(opcode, rewritten, mName, mDesc, isInterface)
                }
            }
        }
    }

    /** Naive substring scan over the raw class bytes: a one-shot gate on a short [needle], as in
     *  [ArtReflectionRewrite]. */
    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
