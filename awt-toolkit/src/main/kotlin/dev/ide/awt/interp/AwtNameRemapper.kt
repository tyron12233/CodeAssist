package dev.ide.awt.interp

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

/**
 * Rewrites a compiled program's `java.awt` / `javax.swing` references onto the owned toolkit, so a program
 * built against the real API runs against this implementation without being recompiled.
 *
 * The toolkit cannot simply BE `java.awt`: no class loader will define a class in `java.*` (ART included), so
 * the packages are mirrored one prefix over and the reference is moved at load time instead. This is the same
 * trick `:layout-preview-impl`'s `BridgeRemapper` uses to reparent a custom `View` onto `BridgeView`, applied
 * to a whole namespace rather than one supertype.
 *
 * The rewrite is whole-namespace on purpose. Remapping only the classes the toolkit implements would leave a
 * program holding a real `javax.swing.JTable` that our `Container.add` cannot accept, and the failure would
 * surface far from its cause; mapping everything means an unimplemented widget fails at resolution, naming
 * itself. [missingFrom] turns that into the up-front list.
 *
 * Only names are rewritten. Method signatures, field descriptors, and the constant pool follow automatically
 * through ASM's [Remapper], and nothing else about the class changes.
 */
object AwtNameRemapper {

    /** The package moves, as internal-name prefixes. Order does not matter: the prefixes are disjoint. */
    private val PREFIXES = listOf(
        "java/awt/" to "dev/ide/awt/",
        "javax/swing/" to "dev/ide/swing/",
    )

    /** ASM's view of [map]. Kept private so nothing about this object's API requires ASM on the classpath. */
    private val asmRemapper = object : Remapper() {
        override fun map(internalName: String): String = AwtNameRemapper.map(internalName)
    }

    /** Whether [internalName] names a class this remapper moves. */
    fun handles(internalName: String): Boolean = PREFIXES.any { internalName.startsWith(it.first) }

    /** The toolkit name for [internalName], or the name unchanged when it is not an AWT or Swing type. */
    fun map(internalName: String): String {
        for ((from, to) in PREFIXES) {
            if (internalName.startsWith(from)) return to + internalName.substring(from.length)
        }
        return internalName
    }

    /** Whether [internalName] is one of the toolkit's own mirrored types, i.e. the inverse of [handles]. */
    fun handlesToolkitName(internalName: String): Boolean = PREFIXES.any { internalName.startsWith(it.second) }

    /** The original AWT or Swing name a toolkit [internalName] stands for, for error messages and for
     *  generating the compile-time API jar (see [SwingApiStubs]). */
    fun originalName(internalName: String): String {
        for ((from, to) in PREFIXES) {
            if (internalName.startsWith(to)) return from + internalName.substring(to.length)
        }
        return internalName
    }

    /**
     * Every class the constant pool of [reader] names.
     *
     * A `CONSTANT_Class` entry (tag 7) holds a UTF8 INDEX, not a class index, so its value is read with
     * `readUTF8` at the entry's own offset; `readClass` there would treat the name index as a class index and
     * come back with an unrelated entry.
     */
    fun classConstants(bytes: ByteArray): List<String> {
        val reader = ClassReader(bytes)
        val names = ArrayList<String>()
        val chars = CharArray(reader.maxStringLength)
        for (i in 1 until reader.itemCount) {
            val offset = reader.getItem(i)
            if (offset == 0 || reader.readByte(offset - 1) != CONSTANT_CLASS) continue
            runCatching { reader.readUTF8(offset, chars) }.getOrNull()?.let { names.add(it) }
        }
        return names
    }

    private const val CONSTANT_CLASS = 7

    /** Rewrite one class file. Returns [bytes] unchanged when it names nothing this remapper moves. */
    fun remap(bytes: ByteArray): ByteArray {
        val reader = ClassReader(bytes)
        // COMPUTE_MAXS/FRAMES are unnecessary: renaming a type changes no stack shape, and recomputing frames
        // would need the whole hierarchy loadable, which is exactly what is not true here.
        val writer = ClassWriter(0)
        reader.accept(ClassRemapper(writer, asmRemapper), 0)
        return writer.toByteArray()
    }

    /**
     * The AWT and Swing types [bytes] references that the toolkit does not implement, as their ORIGINAL names.
     * A host can report these before a run instead of letting the program die partway through building its UI.
     * [isImplemented] answers whether a mapped internal name exists, normally a class-loader probe.
     */
    fun missingFrom(bytes: ByteArray, isImplemented: (String) -> Boolean): List<String> {
        val referenced = classConstants(bytes).mapNotNull { name ->
            // An array type appears as a descriptor rather than a bare internal name.
            val bare = name.trimStart('[').removePrefix("L").removeSuffix(";")
            bare.takeIf { handles(it) }
        }
        return referenced.distinct().filterNot { isImplemented(map(it)) }.sorted()
    }
}
