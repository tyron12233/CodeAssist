package dev.ide.android.support.tasks

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path

/**
 * Generates an Android `R` class as **bytecode** straight from an aapt2-generated `R.java`, instead of feeding
 * it to the Java compiler. With `--extra-packages` aapt2 emits the WHOLE resource table once per dependency
 * package — for a dependency-heavy app that is millions of lines across dozens of huge `R` classes, and ecj
 * retains every binding for the whole invocation, OOMing the (heap-capped, in-process) on-device build. An `R`
 * class is nothing but `public static final int` constants (+ `R.styleable` `int[]` arrays), so emitting it
 * directly with ASM is both far cheaper and the way AGP avoids the same cost (its `R.jar`).
 *
 * The parser is line-oriented because aapt2's output is rigid and machine-generated. Both shapes occur:
 * the app's **final** R (`public static final int x=0x…;`, inlinable) and a library's **non-final** R
 * (`--non-final-ids` → `public static int x=0x…;`), where the fields must stay non-`final` so library code
 * `getstatic`s them and the app-generated final ids win at runtime (decoupled-R). A final field is emitted
 * with a `ConstantValue`; a non-final field is assigned in `<clinit>`.
 * ```
 * package com.example;
 * public final class R {
 *   public static final class attr { public static final int dividerVisible=0x7f010000; … }
 *   public static final class styleable {
 *     public static final int[] ActionBar={ 0x7f03004d, 0x7f030054, … };   // values may span lines
 *     public static final int ActionBar_background=0;
 *   }
 * }
 * ```
 */
object RBytecodeGenerator {

    private class IntField(val name: String, val value: Int, val isFinal: Boolean)
    private class ArrayField(val name: String, val values: IntArray, val isFinal: Boolean)
    private class TypeClass(val name: String) {
        val ints = ArrayList<IntField>()
        val arrays = ArrayList<ArrayField>()   // R.styleable int[] arrays
    }

    private class RModel(val pkg: String, val types: List<TypeClass>)

    /**
     * Package every package's `R` classes from [rJavaFiles] into one jar — the AGP `R.jar` artifact
     * (`compile_and_runtime_not_namespaced_r_class_jar`). Returns the total class count. A class-free jar still
     * gets a manifest entry, since a zero-entry `JarOutputStream` throws `No entries` on ART.
     */
    fun writeJar(rJavaFiles: List<Path>, outJar: Path): Int {
        outJar.parent?.let { Files.createDirectories(it) }
        var total = 0
        java.util.jar.JarOutputStream(Files.newOutputStream(outJar)).use { jos ->
            for (rj in rJavaFiles) {
                val model = parse(read(rj))
                if (model.pkg.isEmpty()) continue
                total += emit(model) { name, bytes ->
                    jos.putNextEntry(java.util.jar.JarEntry("$name.class")); jos.write(bytes); jos.closeEntry()
                }
            }
            if (total == 0) {
                jos.putNextEntry(java.util.jar.JarEntry("META-INF/MANIFEST.MF"))
                jos.write("Manifest-Version: 1.0\r\n\r\n".toByteArray()); jos.closeEntry()
            }
        }
        return total
    }

    private fun read(p: Path): String = String(Files.readAllBytes(p), Charsets.UTF_8)

    private fun parse(text: String): RModel {
        var pkg = ""
        val types = ArrayList<TypeClass>()
        var cur: TypeClass? = null
        var arrName: String? = null            // non-null while accumulating a multi-line int[] literal
        var arrFinal = false
        val arrVals = ArrayList<Int>()

        fun finishArray() {
            cur?.arrays?.add(ArrayField(arrName!!, arrVals.toIntArray(), arrFinal))
            arrName = null; arrVals.clear()
        }
        fun collect(values: String) =
            values.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { arrVals.add(decodeInt(it)) }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (arrName != null) {                              // inside an int[] literal
                collect(line.substringBefore('}'))
                if (line.contains('}')) finishArray()
                continue
            }
            when {
                line.startsWith("package ") ->
                    pkg = line.removePrefix("package ").substringBefore(';').trim()
                // Nested type: `public static [final] class <type> {`. The outer `public final class R` lacks
                // `static`, so it is skipped here and synthesized in [emit]. (`int[]` is matched before `int `.)
                line.startsWith("public static") && " class " in line -> {
                    val name = line.substringAfter(" class ").substringBefore('{').trim()
                    cur = TypeClass(name).also { types.add(it) }
                }
                line.startsWith("public static") && "int[] " in line -> {
                    arrFinal = "final" in line.substringBefore("int[]")
                    val rest = line.substringAfter("int[] ")
                    arrName = rest.substringBefore('=').trim()
                    arrVals.clear()
                    val after = rest.substringAfter('{', "")
                    if (after.isNotEmpty()) {
                        collect(after.substringBefore('}'))
                        if (after.contains('}')) finishArray()
                    }
                }
                line.startsWith("public static") && "int " in line -> {
                    val isFinal = "final" in line.substringBefore("int ")
                    val rest = line.substringAfter("int ").substringBefore(';')
                    val name = rest.substringBefore('=').trim()
                    cur?.ints?.add(IntField(name, decodeInt(rest.substringAfter('=')), isFinal))
                }
            }
        }
        return RModel(pkg, types)
    }

    /** Resource ids are unsigned hex (`0x7f…`, < 2^31 so they fit a positive int); styleable indices decimal. */
    private fun decodeInt(token: String): Int = java.lang.Long.decode(token.trim().removeSuffix(",").trim()).toInt()

    private fun emit(model: RModel, sink: (internalName: String, bytes: ByteArray) -> Unit): Int {
        val rName = "${model.pkg.replace('.', '/')}/R"
        // Outer `R` class: no fields, just declares its nested types.
        sink(rName, classBytes { cw ->
            cw.visit(Opcodes.V1_8, CLASS_ACCESS, rName, null, "java/lang/Object", null)
            model.types.forEach { cw.visitInnerClass("$rName\$${it.name}", rName, it.name, INNER_ACCESS) }
            emitDefaultCtor(cw)
        })
        for (t in model.types) {
            val internal = "$rName\$${t.name}"
            sink(internal, classBytes { cw ->
                // ACC_STATIC for a nested class lives ONLY in the InnerClasses attribute, never in the class's
                // own access_flags — so the class is PUBLIC|FINAL|SUPER and the `static` is recorded below.
                cw.visit(Opcodes.V1_8, CLASS_ACCESS, internal, null, "java/lang/Object", null)
                cw.visitInnerClass(internal, rName, t.name, INNER_ACCESS)
                // final id → ConstantValue (inlinable); non-final id → plain field, assigned in <clinit>.
                t.ints.forEach { f ->
                    cw.visitField(fieldAccess(f.isFinal), f.name, "I", null, if (f.isFinal) f.value else null).visitEnd()
                }
                // `R.styleable` arrays are never constants — always built in <clinit>, regardless of `final`.
                t.arrays.forEach { a -> cw.visitField(fieldAccess(a.isFinal), a.name, "[I", null, null).visitEnd() }
                emitDefaultCtor(cw)
                emitClinit(cw, internal, t.ints.filterNot { it.isFinal }, t.arrays)
            })
        }
        return 1 + model.types.size
    }

    private inline fun classBytes(build: (ClassWriter) -> Unit): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        build(cw)
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun emitDefaultCtor(cw: ClassWriter) {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** Assign non-final scalar ids and build every `int[]` array — nothing to emit if both are empty. */
    private fun emitClinit(cw: ClassWriter, internal: String, nonFinalInts: List<IntField>, arrays: List<ArrayField>) {
        if (nonFinalInts.isEmpty() && arrays.isEmpty()) return
        val mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        mv.visitCode()
        for (f in nonFinalInts) {
            pushInt(mv, f.value)
            mv.visitFieldInsn(Opcodes.PUTSTATIC, internal, f.name, "I")
        }
        for (a in arrays) {
            pushInt(mv, a.values.size)
            mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT)
            a.values.forEachIndexed { i, v ->
                mv.visitInsn(Opcodes.DUP)
                pushInt(mv, i)
                pushInt(mv, v)
                mv.visitInsn(Opcodes.IASTORE)
            }
            mv.visitFieldInsn(Opcodes.PUTSTATIC, internal, a.name, "[I")
        }
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun pushInt(mv: MethodVisitor, v: Int) = when {
        v == -1 -> mv.visitInsn(Opcodes.ICONST_M1)
        v in 0..5 -> mv.visitInsn(Opcodes.ICONST_0 + v)
        v in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(Opcodes.BIPUSH, v)
        v in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(Opcodes.SIPUSH, v)
        else -> mv.visitLdcInsn(v)
    }

    /** `public static [final]` for a field, mirroring the source's id finality. */
    private fun fieldAccess(isFinal: Boolean): Int =
        Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or (if (isFinal) Opcodes.ACC_FINAL else 0)

    /** A class's own `access_flags` (outer R and each nested type): no ACC_STATIC here. */
    private const val CLASS_ACCESS = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER
    /** An `InnerClasses` attribute entry for a `public static final` nested type. */
    private const val INNER_ACCESS = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL
}
