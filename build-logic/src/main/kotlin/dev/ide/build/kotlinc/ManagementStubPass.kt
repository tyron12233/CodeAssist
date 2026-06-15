package dev.ide.build.kotlinc

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * Neutralizes the Kotlin compiler's use of `java.lang.management.*` — a package that does **not** exist on
 * Android's ART (no `ManagementFactory`, no `*MXBean`, no `ThreadInfo`). It can't be stubbed (app classes
 * may not live in `java.*`), so we rewrite the bytecode instead.
 *
 * Discovered by the device spike (`KotlinCompilerArtSpikeTest`): `PerformanceManager` reaches for
 * `ManagementFactory.getThreadMXBean()` etc. and throws the moment those instructions execute (ART resolves
 * lazily). The six compiler classes that touch the package are all observability/diagnostics — perf metrics,
 * thread dumps, low-memory watcher, debugger-attach detection — never codegen, so gutting them is safe.
 *
 * **Strategy: whole-method-body replacement** (not call-site poking). For every method that references
 * `java.lang.management` (by call owner, field type, or `checkcast`/`new` type), the body is discarded and
 * replaced with a trivial `return <default>`. This sidesteps the fragile patterns that call-site edits hit —
 * `lateinit` field reads emit an inline null-check-and-throw whose control flow can't be safely unpicked, and
 * Kotlin's `Intrinsics.checkNotNull` assertions fire on injected nulls. A gutted method never touches the
 * package at all, so none of that arises.
 *
 * Only **non-constructor** methods are rewritten; none of the six classes touch management in an `<init>`/
 * `<clinit>` (verified), so constructors and field initializers are left byte-for-byte intact. Return values:
 * `void`→return, primitives→0, objects/arrays→null, except `org.jetbrains.kotlin.util.Time` (the non-null
 * return of `PerformanceManager.currentTime()`, consumed by its non-gutted `<init>`) → a real `new Time(0,0,0)`
 * so callers don't NPE on a null timestamp.
 */
class ManagementStubPass : ArtPatchPass {

    override val name: String = "java-lang-management-stub"

    private val targets: Set<String> = setOf(
        "org.jetbrains.kotlin.util.PerformanceManager",
        "org.jetbrains.kotlin.build.report.metrics.BuildMetricsReporterKt",
        "org.jetbrains.kotlin.com.intellij.diagnostic.ThreadDumper",
        "org.jetbrains.kotlin.com.intellij.openapi.util.LowMemoryWatcherManager",
        "org.jetbrains.kotlin.com.intellij.openapi.util.LowMemoryWatcherManager\$2",
        "org.jetbrains.kotlin.com.intellij.util.DebugAttachDetectorArgs",
    )

    override fun handles(classFqn: String): Boolean = classFqn in targets

    override fun visitor(classFqn: String, next: ClassVisitor): ClassVisitor = StubbingClassNode(next)

    /** Buffers the class (tree API), rewrites the offending method bodies on [visitEnd], forwards downstream. */
    private class StubbingClassNode(private val downstream: ClassVisitor) : ClassNode(Opcodes.ASM9) {
        override fun visitEnd() {
            for (method in methods) {
                if (method.name == "<init>" || method.name == "<clinit>") continue
                if (!touchesManagement(method)) continue
                val custom = customBodyFor(name, method)
                if (custom != null) applyBody(method, custom, maxStack = 2) else stubOut(method)
            }
            accept(downstream)
        }
    }

    private companion object {
        const val INTERNAL_PREFIX = "java/lang/management/"
        const val DESCRIPTOR_FRAGMENT = "Ljava/lang/management/"
        const val TIME_INTERNAL = "org/jetbrains/kotlin/util/Time"
        const val PERFORMANCE_MANAGER = "org/jetbrains/kotlin/util/PerformanceManager"

        /**
         * Does [method] *execute* anything in `java.lang.management` — a call into the package, or a read/write
         * of a management-typed field? (Type-only references — `checkcast`/`new`/`anewarray` of a management
         * type — live exclusively in helper methods reached from these call/field sites, so gutting the latter
         * makes the former dead; we needn't detect them, which also avoids ASM's `TypeInsnNode.type` /
         * `AbstractInsnNode.getType()` name clash in Kotlin.)
         */
        fun touchesManagement(method: MethodNode): Boolean {
            var insn = method.instructions.first
            while (insn != null) {
                val hit = when (insn) {
                    is MethodInsnNode -> insn.owner.startsWith(INTERNAL_PREFIX)
                    is FieldInsnNode -> insn.owner.startsWith(INTERNAL_PREFIX) ||
                        insn.desc.contains(DESCRIPTOR_FRAGMENT)
                    else -> false
                }
                if (hit) return true
                insn = insn.next
            }
            return false
        }

        /**
         * A few methods do legitimate non-management work alongside the management call we're removing, so a
         * blank stub would drop state the rest of the class relies on. Return a hand-written body for those;
         * `null` falls through to the trivial default-return [stubOut].
         *
         * `PerformanceManager.initializeCurrentThread()` sets `lateinit var thread = Thread.currentThread()`
         * (read later by `ensureNotFinalizedAndSameThread`) *and* wires up a `ThreadMXBean`. Keep the thread
         * assignment, drop the bean: `this.thread = Thread.currentThread(); return`.
         */
        fun customBodyFor(ownerInternalName: String, method: MethodNode): InsnList? {
            if (ownerInternalName == PERFORMANCE_MANAGER &&
                method.name == "initializeCurrentThread" && method.desc == "()V"
            ) {
                return InsnList().apply {
                    add(VarInsnNode(Opcodes.ALOAD, 0))
                    add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false))
                    add(FieldInsnNode(Opcodes.PUTFIELD, PERFORMANCE_MANAGER, "thread", "Ljava/lang/Thread;"))
                    add(InsnNode(Opcodes.RETURN))
                }
            }
            return null
        }

        /** Replace [method]'s body with `return <default for its return type>`. */
        fun stubOut(method: MethodNode) {
            applyBody(method, trivialBody(Type.getReturnType(method.desc)), maxStack = 8)
        }

        private fun applyBody(method: MethodNode, body: InsnList, maxStack: Int) {
            method.instructions = body
            method.tryCatchBlocks = ArrayList()
            method.localVariables = ArrayList()
            method.visibleLocalVariableAnnotations = null
            method.invisibleLocalVariableAnnotations = null
            // AGP recomputes frames (and maxs) for instrumented methods, but provide safe values anyway.
            method.maxStack = maxStack
            method.maxLocals = (Type.getArgumentsAndReturnSizes(method.desc) ushr 2) + 1
        }

        private fun trivialBody(returnType: Type): InsnList = InsnList().apply {
            if (returnType.internalName == TIME_INTERNAL) {
                // new org.jetbrains.kotlin.util.Time(0L, 0L, 0L) — a valid, non-null timestamp.
                add(TypeInsnNode(Opcodes.NEW, TIME_INTERNAL))
                add(InsnNode(Opcodes.DUP))
                add(InsnNode(Opcodes.LCONST_0))
                add(InsnNode(Opcodes.LCONST_0))
                add(InsnNode(Opcodes.LCONST_0))
                add(MethodInsnNode(Opcodes.INVOKESPECIAL, TIME_INTERNAL, "<init>", "(JJJ)V", false))
                add(InsnNode(Opcodes.ARETURN))
                return@apply
            }
            when (returnType.sort) {
                Type.VOID -> add(InsnNode(Opcodes.RETURN))
                Type.LONG -> { add(InsnNode(Opcodes.LCONST_0)); add(InsnNode(Opcodes.LRETURN)) }
                Type.FLOAT -> { add(InsnNode(Opcodes.FCONST_0)); add(InsnNode(Opcodes.FRETURN)) }
                Type.DOUBLE -> { add(InsnNode(Opcodes.DCONST_0)); add(InsnNode(Opcodes.DRETURN)) }
                Type.OBJECT, Type.ARRAY -> { add(InsnNode(Opcodes.ACONST_NULL)); add(InsnNode(Opcodes.ARETURN)) }
                else -> { add(InsnNode(Opcodes.ICONST_0)); add(InsnNode(Opcodes.IRETURN)) } // boolean/char/byte/short/int
            }
        }
    }
}
