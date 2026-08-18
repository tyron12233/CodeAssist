package dev.ide.awt

import dev.ide.awt.interp.AwtNameRemapper
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The name rewrite that lets a program compiled against the real Swing resolve against the owned toolkit. */
class AwtNameRemapperTest {

    @Test fun awtAndSwingPackagesMoveAndNothingElseDoes() {
        assertEquals("dev/ide/awt/Color", AwtNameRemapper.map("java/awt/Color"))
        assertEquals("dev/ide/awt/event/ActionListener", AwtNameRemapper.map("java/awt/event/ActionListener"))
        assertEquals("dev/ide/swing/JFrame", AwtNameRemapper.map("javax/swing/JFrame"))
        assertEquals("java/lang/String", AwtNameRemapper.map("java/lang/String"))
        assertEquals("java/util/ArrayList", AwtNameRemapper.map("java/util/ArrayList"))
        // A near miss that must not move: the package is javax.sound, not javax.swing.
        assertEquals("javax/sound/sampled/Clip", AwtNameRemapper.map("javax/sound/sampled/Clip"))
    }

    @Test fun theMappingRoundTripsBackToTheOriginalName() {
        for (name in listOf("java/awt/Graphics2D", "javax/swing/JButton", "java/awt/event/MouseEvent")) {
            assertEquals(name, AwtNameRemapper.originalName(AwtNameRemapper.map(name)))
        }
    }

    @Test fun handlesOnlyClaimsNamesItMoves() {
        assertTrue(AwtNameRemapper.handles("javax/swing/JPanel"))
        assertTrue(AwtNameRemapper.handles("java/awt/Font"))
        assertFalse(AwtNameRemapper.handles("java/lang/Object"))
        assertFalse(AwtNameRemapper.handles("dev/ide/swing/JPanel"))
    }

    @Test fun aCompiledClassLosesEveryAwtReference() {
        val original = bytesOf("swingfixture/SwingFixture")
        assertTrue(referencedTypes(original).any { it.startsWith("javax/swing/") }, "the fixture starts with real Swing")

        val remapped = AwtNameRemapper.remap(original)
        val types = referencedTypes(remapped)

        assertTrue(types.none { it.startsWith("javax/swing/") || it.startsWith("java/awt/") }, "left behind: $types")
        assertContains(types, "dev/ide/swing/JFrame")
        assertContains(types, "dev/ide/awt/BorderLayout")
        // Types the outer class never names stay out of its pool; the drawing types live in the nested one.
        val panelTypes = referencedTypes(AwtNameRemapper.remap(bytesOf("swingfixture/SwingFixture\$DrawPanel")))
        assertContains(panelTypes, "dev/ide/awt/Color")
        assertContains(panelTypes, "dev/ide/awt/Graphics2D")
        assertContains(panelTypes, "dev/ide/awt/RenderingHints")
    }

    @Test fun theSuperclassOfARemappedSubclassIsTheToolkitClass() {
        val remapped = AwtNameRemapper.remap(bytesOf("swingfixture/SwingFixture\$DrawPanel"))
        assertEquals("dev/ide/swing/JPanel", ClassReader(remapped).superName)
    }

    @Test fun theClassKeepsItsOwnNameAndItsMethodDescriptors() {
        val remapped = AwtNameRemapper.remap(bytesOf("swingfixture/SwingFixture\$DrawPanel"))
        val reader = ClassReader(remapped)
        assertEquals("swingfixture/SwingFixture\$DrawPanel", reader.className, "user classes do not move")

        val descriptors = ArrayList<String>()
        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor? {
                if (name == "paintComponent") descriptors.add(descriptor)
                return null
            }
        }, 0)

        assertEquals(listOf("(Ldev/ide/awt/Graphics;)V"), descriptors, "a signature follows its types")
    }

    @Test fun unimplementedWidgetsAreReportedByTheirOriginalNames() {
        val missing = AwtNameRemapper.missingFrom(bytesOf("swingfixture/UnsupportedWidgetFixture")) { mapped ->
            runCatching { Class.forName(mapped.replace('/', '.'), false, javaClass.classLoader) }.isSuccess
        }

        assertEquals(listOf("javax/swing/JTable"), missing, "JFrame is implemented; JTable is not, and says so")
    }

    @Test fun aFullySupportedProgramReportsNothingMissing() {
        val missing = AwtNameRemapper.missingFrom(bytesOf("swingfixture/SwingFixture")) { mapped ->
            runCatching { Class.forName(mapped.replace('/', '.'), false, javaClass.classLoader) }.isSuccess
        }

        assertEquals(emptyList(), missing, "the fixture uses only implemented widgets")
    }

    private fun bytesOf(internalName: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("$internalName.class")!!.use { it.readBytes() }

    /** Every class the constant pool names, which is what a rewrite has to have caught. */
    private fun referencedTypes(bytes: ByteArray): Set<String> =
        AwtNameRemapper.classConstants(bytes).toSet()
}
