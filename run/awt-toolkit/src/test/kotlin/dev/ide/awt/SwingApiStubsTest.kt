package dev.ide.awt

import dev.ide.awt.interp.SwingApiStubs
import org.objectweb.asm.ClassReader
import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The compile-time API jar is what a Swing project is compiled against wherever the platform has no Swing of
 * its own, which on device is always (`android.jar` carries none, and there is no JDK to fall back to). A
 * dangling reference in it is a resolution error waiting for the first program that touches that member, so
 * the jar is checked for one directly rather than by compiling something and hoping.
 */
class SwingApiStubsTest {

    private val jar: File by lazy {
        val classes = File("build/classes/kotlin/main")
        assertTrue(classes.isDirectory, "the toolkit must be compiled first; looked in ${classes.absolutePath}")
        val out = Files.createTempDirectory("swing-api").resolve("stubs.jar").toFile()
        val count = SwingApiStubs.generate(classes, out)
        assertTrue(count > 0, "generated nothing")
        out
    }

    @Test fun theToolkitIsPublishedUnderTheRealApiNames() {
        val entries = JarFile(jar).use { it.entries().toList().map { e -> e.name } }
        for (expected in listOf(
            "java/awt/Color.class", "java/awt/Graphics2D.class", "java/awt/BorderLayout.class",
            "java/awt/event/ActionListener.class",
            "javax/swing/JFrame.class", "javax/swing/JPanel.class", "javax/swing/JButton.class",
            "javax/swing/WindowConstants.class",
        )) {
            assertContains(entries, expected)
        }
        assertTrue(entries.none { it.startsWith("dev/ide/") }, "the toolkit's own names must not survive: $entries")
    }

    @Test fun theToolkitsOwnPlumbingIsNotPublishedAsApi() {
        val entries = JarFile(jar).use { it.entries().toList().map { e -> e.name } }
        // These exist in the toolkit but not in real AWT/Swing, so a program must never see them.
        for (internal in listOf(
            "java/awt/CanvasGraphics.class", "java/awt/ToolkitWindows.class", "java/awt/NoCanvas.class",
            "java/awt/Surface.class", "javax/swing/ToolkitEventQueue.class",
        )) {
            assertTrue(internal !in entries, "$internal should not be in the API jar")
        }
        assertTrue(entries.none { "Companion" in it }, "Kotlin companions are not API: $entries")
    }

    @Test fun nothingInTheJarReferencesATypeItDoesNotShip() {
        // Every class the constant pool names must be either in this jar or in the real platform. Anything
        // else would fail to resolve the moment a program's code path reached it.
        val jarFile = JarFile(jar)
        val shipped = jarFile.entries().toList().map { it.name.removeSuffix(".class") }.toSet()
        val dangling = LinkedHashSet<String>()
        jarFile.use { file ->
            for (name in shipped) {
                val bytes = file.getInputStream(file.getJarEntry("$name.class")).use { it.readBytes() }
                for (referenced in classConstantsOf(bytes)) {
                    val bare = referenced.trimStart('[').removePrefix("L").removeSuffix(";")
                    if (bare in shipped || bare.startsWith("[")) continue
                    if (!existsOnPlatform(bare)) dangling.add("$name -> $bare")
                }
            }
        }
        assertEquals(emptySet(), dangling, "the API jar names types nothing can resolve")
    }

    @Test fun theConstantsAProgramActuallyWritesAreKept() {
        // `WindowConstants.EXIT_ON_CLOSE`, `Color.WHITE`, `Font.BOLD` and `BorderLayout.CENTER` are in the
        // first five lines of most Swing programs, and Kotlin puts them somewhere non-obvious.
        assertTrue(hasField("javax/swing/WindowConstants", "EXIT_ON_CLOSE"))
        assertTrue(hasField("javax/swing/JFrame", "EXIT_ON_CLOSE"))
        assertTrue(hasField("java/awt/Color", "WHITE"))
        assertTrue(hasField("java/awt/Color", "DARK_GRAY"))
        assertTrue(hasField("java/awt/Font", "BOLD"))
        assertTrue(hasField("java/awt/BorderLayout", "CENTER"))
        assertTrue(hasField("java/awt/RenderingHints", "KEY_ANTIALIASING"))
    }

    private fun hasField(internalName: String, field: String): Boolean {
        val bytes = JarFile(jar).use { f ->
            f.getInputStream(f.getJarEntry("$internalName.class")).use { it.readBytes() }
        }
        var found = false
        ClassReader(bytes).accept(object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            override fun visitField(
                access: Int, name: String, descriptor: String, signature: String?, value: Any?,
            ): org.objectweb.asm.FieldVisitor? {
                if (name == field) found = true
                return null
            }
        }, 0)
        return found
    }

    private fun classConstantsOf(bytes: ByteArray): List<String> =
        dev.ide.awt.interp.AwtNameRemapper.classConstants(bytes)

    private fun existsOnPlatform(internalName: String): Boolean = runCatching {
        Class.forName(internalName.replace('/', '.'), false, ClassLoader.getPlatformClassLoader())
    }.isSuccess
}
