package com.tyron.builder.symbols

import com.android.SdkConstants
import com.android.resources.ResourceType
import com.tyron.builder.common.symbols.Symbol
import com.tyron.builder.common.symbols.SymbolTable
import com.tyron.builder.common.symbols.canonicalizeValueResourceName
import com.tyron.builder.packaging.JarCreator
import com.tyron.builder.packaging.JarFlinger
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassWriter.COMPUTE_MAXS
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type.INT_TYPE
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method
import java.io.IOException
import java.nio.file.Path
import java.util.*
import java.util.zip.Deflater.NO_COMPRESSION

@Throws(IOException::class)
fun exportToCompiledJava(tables: Iterable<SymbolTable>, outJar: Path, finalIds: Boolean = false) {
    JarFlinger(outJar).use { jarCreator ->
        // NO_COMPRESSION because R.jar isn't packaged into final APK or AAR
        jarCreator.setCompressionLevel(NO_COMPRESSION)
        val mergedTables = tables.groupBy { it.tablePackage }.map { SymbolTable.merge(it.value) }
        mergedTables.forEach { table ->
            exportToCompiledJava(table, jarCreator, finalIds)
        }
    }
}

@Throws(IOException::class)
fun exportToCompiledJava(
    table: SymbolTable,
    jarMerger: JarCreator,
    finalIds: Boolean = false
) {
    val resourceTypes = EnumSet.noneOf(ResourceType::class.java)
    for (resType in ResourceType.values()) {
        // Don't write empty R$ classes.
        val bytes = generateResourceTypeClass(table, resType, finalIds) ?: continue
        resourceTypes.add(resType)
        val innerR = internalName(table, resType)
        jarMerger.addEntry(innerR + SdkConstants.DOT_CLASS, bytes.inputStream())
    }

    // Generate and write the main R class file.
    val packageR = internalName(table, null)
    jarMerger.addEntry(
        packageR + SdkConstants.DOT_CLASS,
        generateOuterRClass(resourceTypes, packageR).inputStream())
}


private fun generateOuterRClass(resourceTypes: EnumSet<ResourceType>, packageR: String): ByteArray {
    val cw = ClassWriter(COMPUTE_MAXS)
    cw.visit(
            Opcodes.V1_8,
            ACC_PUBLIC + ACC_FINAL + ACC_SUPER,
            packageR, null,
            "java/lang/Object", null)

    for (rt in resourceTypes) {
        cw.visitInnerClass(
                packageR + "$" + rt.getName(),
                packageR,
                rt.getName(),
                ACC_PUBLIC + ACC_FINAL + ACC_STATIC)
    }

    // Constructor
    val mv: MethodVisitor
    mv = cw.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null)
    mv.visitCode()
    mv.visitVarInsn(ALOAD, 0)
    mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
    mv.visitInsn(RETURN)
    mv.visitMaxs(0, 0)
    mv.visitEnd()

    cw.visitEnd()

    return cw.toByteArray()
}

private fun generateResourceTypeClass(
    table: SymbolTable, resType: ResourceType, finalIds: Boolean): ByteArray? {
    val symbols = table.getSymbolByResourceType(resType)
    if (symbols.isEmpty()) {
        return null
    }
    val cw = ClassWriter(COMPUTE_MAXS)
    val internalName = internalName(table, resType)
    cw.visit(
            Opcodes.V1_8,
            ACC_PUBLIC + ACC_FINAL + ACC_SUPER,
            internalName, null,
            "java/lang/Object", null)

    cw.visitInnerClass(
            internalName,
            internalName(table, null),
            resType.getName(),
            ACC_PUBLIC + ACC_FINAL + ACC_STATIC)

    if (resType == ResourceType.ATTR) {
        // Starting S, the android attributes might not have a stable ID and a reference to the
        // android.R.attr class should be used instead of a int value.
        cw.visitInnerClass(
                "android/R\$attr",
                "android/R",
                resType.getName(),
                ACC_PUBLIC + ACC_FINAL + ACC_STATIC)
    }

    for (s in symbols) {
        cw.visitField(
                ACC_PUBLIC + ACC_STATIC + if (finalIds) ACC_FINAL else 0,
                s.canonicalName,
                s.javaType.desc,
                null,
                if (s is Symbol.StyleableSymbol) null else s.intValue
        )
                .visitEnd()

        if (s is Symbol.StyleableSymbol) {
            val children = s.children
            for ((i, child) in children.withIndex()) {
                cw.visitField(
                        ACC_PUBLIC + ACC_STATIC + if (finalIds) ACC_FINAL else 0,
                        "${s.canonicalName}_${canonicalizeValueResourceName(child)}",
                        "I",
                        null,
                        i)
            }
        }
    }

    // Constructor
    val init = cw.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null)
    init.visitCode()
    init.visitVarInsn(ALOAD, 0)
    init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
    init.visitInsn(RETURN)
    init.visitMaxs(0, 0)
    init.visitEnd()

    // init method
    if (resType == ResourceType.STYLEABLE) {
        val method = Method("<clinit>", "()V")
        val clinit = GeneratorAdapter(ACC_PUBLIC.or(ACC_STATIC), method, null, null, cw)
        clinit.visitCode()
        for (s in symbols) {
            s as Symbol.StyleableSymbol
            val values = s.values
            clinit.push(values.size)
            clinit.newArray(INT_TYPE)

            for ((i, value) in values.withIndex()) {
                if (isUnstableAndroidAttr(value, s.children[i])) {
                    // For unstable android attributes a reference to android.R.attr should be used
                    // instead of the value (0).
                    val name = s.children[i].substringAfter("android").drop(1)
                    clinit.dup()
                    clinit.push(i)
                    clinit.visitFieldInsn(
                            GETSTATIC,
                            "android/R\$attr",
                            canonicalizeValueResourceName(name),
                            "I")
                    clinit.arrayStore(INT_TYPE)
                } else {
                    clinit.dup()
                    clinit.push(i)
                    clinit.push(value)
                    clinit.arrayStore(INT_TYPE)
                }
            }

            clinit.visitFieldInsn(PUTSTATIC, internalName, s.canonicalName, "[I")
        }
        clinit.returnValue()
        clinit.endMethod()
    }

    cw.visitEnd()

    return cw.toByteArray()
}

private fun isUnstableAndroidAttr(value: Int, name: String) : Boolean {
    // Only platform attributes should have ID value of 0, but check the prefix to
    // be safe. Sometimes the name is already canonicalized, so either "android."
    // or "android_" can be used.
    return value == 0 && (
            name.startsWith("android.")
                    || name.startsWith("android_")
                    || name.startsWith("android:"))
}

private fun internalName(table: SymbolTable, type: ResourceType?): String {
    val className = if (type == null) "R" else "R$${type.getName()}"

    return if (table.tablePackage.isEmpty()) {
        className
    } else {
        "${table.tablePackage.replace(".", "/")}/$className"
    }
}