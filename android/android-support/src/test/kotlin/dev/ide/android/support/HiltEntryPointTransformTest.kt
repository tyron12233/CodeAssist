package dev.ide.android.support

import dev.ide.android.support.tasks.TransformHiltClassesTask
import dev.ide.android.support.tools.HiltEntryPoints
import dev.ide.build.TaskName
import dev.ide.build.TaskResult
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `@AndroidEntryPoint` superclass rewrite ([HiltEntryPoints]) and the task that applies it
 * ([TransformHiltClassesTask]): the half of Hilt that the Gradle plugin does in an AGP build, and without
 * which the processor's generated `Hilt_` classes are dead code and nothing is ever injected.
 *
 * The fixtures are assembled with ASM rather than compiled, so the test needs neither Hilt nor an Android SDK.
 */
class HiltEntryPointTransformTest {

    private companion object {
        const val ANDROID_ENTRY_POINT = "Ldagger/hilt/android/AndroidEntryPoint;"
        const val HILT_ANDROID_APP = "Ldagger/hilt/android/HiltAndroidApp;"
        const val ACTIVITY = "androidx/activity/ComponentActivity"
    }

    @Test
    fun rewritesAnAnnotatedClassToExtendItsGeneratedHiltBase() {
        val original = entryPointClass("com/example/MainActivity", ACTIVITY, classAnnotation = ANDROID_ENTRY_POINT)
        val rewritten = assertNotNull(HiltEntryPoints.rewriteSuperclass(original), "an @AndroidEntryPoint class must be rewritten")

        val read = ClassFacts(rewritten)
        assertEquals("com/example/Hilt_MainActivity", read.superName)
        // Both the `super()` constructor call and the `super.onCreate()` call must follow the new superclass:
        // the verifier rejects an INVOKESPECIAL whose owner is no longer a superclass of this class.
        assertContentEquals(
            listOf("com/example/Hilt_MainActivity", "com/example/Hilt_MainActivity"),
            read.invokeSpecialOwners,
        )
    }

    @Test
    fun rewritesAHiltAndroidAppTheSameWay() {
        val rewritten = HiltEntryPoints.rewriteSuperclass(
            entryPointClass("com/example/App", "android/app/Application", classAnnotation = HILT_ANDROID_APP)
        )
        assertEquals("com/example/Hilt_App", ClassFacts(rewritten!!).superName)
    }

    @Test
    fun leavesAClassWithoutTheAnnotationUntouched() {
        assertNull(
            HiltEntryPoints.rewriteSuperclass(entryPointClass("com/example/Plain", ACTIVITY, classAnnotation = null)),
            "a class with no Hilt annotation must be packaged byte-for-byte",
        )
    }

    @Test
    fun doesNotRewriteAClassThatMerelyMentionsTheAnnotationType() {
        // The cheap constant-pool probe fires here (the descriptor IS in the pool), so this is what proves the
        // transform confirms a real CLASS-level annotation before touching anything.
        val onlyOnAMethod = entryPointClass("com/example/Plain", ACTIVITY, methodAnnotation = ANDROID_ENTRY_POINT)
        assertNull(HiltEntryPoints.rewriteSuperclass(onlyOnAMethod))
    }

    @Test
    fun isIdempotent() {
        // A project written for the documented no-plugin setup already extends `Hilt_MainActivity` in source.
        // Rewriting that must be a no-op, not a second `Hilt_Hilt_` hop.
        val alreadyRewritten = entryPointClass(
            "com/example/MainActivity", "com/example/Hilt_MainActivity", classAnnotation = ANDROID_ENTRY_POINT,
        )
        val read = ClassFacts(HiltEntryPoints.rewriteSuperclass(alreadyRewritten)!!)
        assertEquals("com/example/Hilt_MainActivity", read.superName)
        assertContentEquals(
            listOf("com/example/Hilt_MainActivity", "com/example/Hilt_MainActivity"),
            read.invokeSpecialOwners,
        )
    }

    @Test
    fun flattensANestedEntryPointIntoATopLevelHiltName() {
        // The processor generates a nested entry point's base as a top-level class in the same package.
        assertEquals("com/example/Hilt_Outer_Inner", HiltEntryPoints.generatedBaseClass("com/example/Outer\$Inner"))
        assertEquals("Hilt_Root", HiltEntryPoints.generatedBaseClass("Root"))
    }

    @Test
    fun theTaskMirrorsEveryOutputAndRewritesOnlyTheEntryPoints() {
        withTempDir("hilt-transform") { dir ->
            val javaOut = dir.resolve("classes")
            val kotlinOut = dir.resolve("kotlin-classes")
            val out = dir.resolve("hilt-classes")
            writeClass(javaOut, "com/example/Hilt_MainActivity.class", entryPointClass("com/example/Hilt_MainActivity", ACTIVITY, null))
            writeClass(kotlinOut, "com/example/MainActivity.class", entryPointClass("com/example/MainActivity", ACTIVITY, ANDROID_ENTRY_POINT))
            writeClass(kotlinOut, "com/example/Plain.class", entryPointClass("com/example/Plain", ACTIVITY, null))
            // Non-class output (kotlinc's module marker) must be carried over: this dir stands in for the whole
            // compile output, so anything dropped here is dropped from the APK.
            Files.createDirectories(kotlinOut.resolve("META-INF"))
            Files.writeString(kotlinOut.resolve("META-INF/main.kotlin_module"), "module")

            val task = TransformHiltClassesTask(TaskName(":app:transformHiltClasses"), listOf(javaOut, kotlinOut), out)
            assertEquals(TaskResult.Success, runBlocking { task.execute(SimpleTaskContext()) })

            assertEquals(
                "com/example/Hilt_MainActivity",
                ClassFacts(Files.readAllBytes(out.resolve("com/example/MainActivity.class"))).superName,
            )
            // Everything else is copied byte-for-byte.
            assertContentEquals(
                Files.readAllBytes(kotlinOut.resolve("com/example/Plain.class")),
                Files.readAllBytes(out.resolve("com/example/Plain.class")),
            )
            assertTrue(Files.isRegularFile(out.resolve("META-INF/main.kotlin_module")))
            assertTrue(Files.isRegularFile(out.resolve("com/example/Hilt_MainActivity.class")))
        }
    }

    @Test
    fun theTaskLeavesAnEntryPointAloneWhenItsHiltBaseWasNeverGenerated() {
        // If the processor didn't run (an IDE build that doesn't bundle it, a module that never declared the
        // runtime), re-pointing the class at a `Hilt_` type that isn't in the output would turn "nothing is
        // injected" into a NoClassDefFoundError at launch.
        withTempDir("hilt-transform-ungenerated") { dir ->
            val classes = dir.resolve("classes")
            val out = dir.resolve("hilt-classes")
            val original = entryPointClass("com/example/MainActivity", ACTIVITY, ANDROID_ENTRY_POINT)
            writeClass(classes, "com/example/MainActivity.class", original)

            val task = TransformHiltClassesTask(TaskName(":app:transformHiltClasses"), listOf(classes), out)
            val log = StringBuilder()
            assertEquals(TaskResult.Success, runBlocking { task.execute(SimpleTaskContext(log = { log.appendLine(it) })) })

            assertContentEquals(original, Files.readAllBytes(out.resolve("com/example/MainActivity.class")))
            assertTrue("Hilt_MainActivity" in log.toString(), "the skipped entry point must be reported:\n$log")
        }
    }

    @Test
    fun theTaskDropsACopyWhoseSourceClassIsGone() {
        withTempDir("hilt-transform-prune") { dir ->
            val classes = dir.resolve("classes")
            val out = dir.resolve("hilt-classes")
            writeClass(classes, "com/example/MainActivity.class", entryPointClass("com/example/MainActivity", ACTIVITY, ANDROID_ENTRY_POINT))
            writeClass(classes, "com/example/Gone.class", entryPointClass("com/example/Gone", ACTIVITY, null))

            val task = TransformHiltClassesTask(TaskName(":app:transformHiltClasses"), listOf(classes), out)
            runBlocking { task.execute(SimpleTaskContext()) }
            assertTrue(Files.isRegularFile(out.resolve("com/example/Gone.class")))

            // A renamed/deleted type must not survive in the dir that stands in for the compile output.
            Files.delete(classes.resolve("com/example/Gone.class"))
            assertEquals(TaskResult.Success, runBlocking { task.execute(SimpleTaskContext()) })
            assertFalse(Files.exists(out.resolve("com/example/Gone.class")))
            assertTrue(Files.isRegularFile(out.resolve("com/example/MainActivity.class")))
        }
    }

    // --- fixtures -----------------------------------------------------------------------------------------

    /** A class extending [superName] with a `super()` constructor and a `super.onCreate()` override. */
    private fun entryPointClass(
        name: String,
        superName: String,
        classAnnotation: String? = null,
        methodAnnotation: String? = null,
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, name, null, superName, null)
        // CLASS-retention, so it lands in the bytecode as an *invisible* annotation, the form the transform
        // actually has to recognize.
        classAnnotation?.let { cw.visitAnnotation(it, false).visitEnd() }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "onCreate", "()V", null, null).apply {
            methodAnnotation?.let { visitAnnotation(it, false).visitEnd() }
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "onCreate", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun writeClass(root: Path, relPath: String, bytes: ByteArray) {
        val f = root.resolve(relPath)
        Files.createDirectories(f.parent)
        Files.write(f, bytes)
    }

    /** The superclass and every `INVOKESPECIAL` owner of a class, read back with ASM. */
    private class ClassFacts(bytes: ByteArray) {
        var superName: String? = null
            private set
        val invokeSpecialOwners = ArrayList<String>()

        init {
            ClassReader(bytes).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visit(
                        version: Int, access: Int, name: String, signature: String?, superName: String?,
                        interfaces: Array<out String>?,
                    ) {
                        this@ClassFacts.superName = superName
                    }

                    override fun visitMethod(
                        access: Int, name: String?, descriptor: String?, signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(
                            opcode: Int, owner: String, mName: String?, mDesc: String?, isInterface: Boolean,
                        ) {
                            if (opcode == Opcodes.INVOKESPECIAL) invokeSpecialOwners += owner
                        }
                    }
                },
                0,
            )
        }
    }
}
