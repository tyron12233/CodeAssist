package com.tyron.builder.symbols

import com.android.SdkConstants
import com.android.ide.common.symbols.*
import com.tyron.builder.packaging.JarCreator
import com.tyron.builder.packaging.JarFlinger
import com.android.resources.ResourceType
import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.ImmutableList
import org.gradle.util.internal.GFileUtils
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassWriter.COMPUTE_MAXS
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Opcodes.ACC_FINAL
import org.objectweb.asm.Opcodes.ACC_PRIVATE
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.GETSTATIC
import org.objectweb.asm.Opcodes.INVOKESPECIAL
import org.objectweb.asm.Opcodes.PUTSTATIC
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.Type.INT_TYPE
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method
import java.io.File
import java.io.IOException
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.util.EnumSet
import java.util.zip.Deflater.NO_COMPRESSION

@Throws(IOException::class)
fun exportToCompiledJava(
    tables: Iterable<SymbolTable>,
    outJar: Path,
    finalIds: Boolean = false,
    rPackage: String? = null,
) {
    JarFlinger(outJar).use { jarCreator ->
        // NO_COMPRESSION because R.jar isn't packaged into final APK or AAR
        jarCreator.setCompressionLevel(NO_COMPRESSION)
        val mergedTables = tables.groupBy { it.tablePackage }.map { SymbolTable.merge(it.value) }
        mergedTables.forEach { table ->
            exportToCompiledJava(table, jarCreator, finalIds, rPackage)
        }
    }
}

@Throws(IOException::class)
fun exportToCompiledJava(
    table: SymbolTable,
    jarMerger: JarCreator,
    finalIds: Boolean = false,
    rPackage: String? = null,
) {
    val resourceTypes = EnumSet.noneOf(ResourceType::class.java)
    for (resType in ResourceType.values()) {
        // Don't write empty R$ classes.
        val bytes = generateResourceTypeClass(table, resType, finalIds, rPackage) ?: continue
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
    table: SymbolTable, resType: ResourceType, finalIds: Boolean, rPackage: String?): ByteArray? {
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
            if (s is Symbol.StyleableSymbol || rPackage != null) null else s.intValue
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
    if (resType == ResourceType.STYLEABLE || rPackage != null) {
        val method = Method("<clinit>", "()V")
        val clinit = GeneratorAdapter(ACC_PUBLIC.or(ACC_STATIC), method, null, null, cw)
        clinit.visitCode()
        if (rPackage != null) {
            clinit.visitFieldInsn(GETSTATIC, rPackage.replace(".", "/") + "/RPackage", "packageId", "I")
            clinit.storeLocal(1, INT_TYPE)
        }

        for (s in symbols) {
            if (resType == ResourceType.STYLEABLE) {
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
                        if (rPackage != null) {
                            clinit.loadLocal(1)
                            clinit.visitInsn(Opcodes.IADD)
                        }
                        clinit.arrayStore(INT_TYPE)
                    }
                }

                clinit.visitFieldInsn(PUTSTATIC, internalName, s.canonicalName, "[I")
            } else {
                clinit.push(s.intValue)
                clinit.loadLocal(1)
                clinit.visitInsn(Opcodes.IADD)
                clinit.visitFieldInsn(PUTSTATIC, internalName, s.canonicalName, "I")
            }
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

/**
 * Write RPackage class for privacy sandbox SDKs
 *
 * See b/243502800
 */
fun writeRPackages(packageNameToId: Map<String, Int>, outJar: Path,) {
    JarFlinger(outJar).use { jarCreator ->
        // NO_COMPRESSION because RPackage.jar isn't packaged into final APK or AAR
        jarCreator.setCompressionLevel(NO_COMPRESSION)
        packageNameToId.forEach { (packageName, packageId) ->
            val cw = ClassWriter(COMPUTE_MAXS)
            val internalName = packageName.replace(".", "/")+ "/" + "RPackage"
            cw.visit(
                Opcodes.V1_8,
                ACC_PUBLIC + ACC_FINAL + ACC_SUPER,
                internalName, null,
                "java/lang/Object", null)
            cw.visitField(ACC_PUBLIC + ACC_STATIC + ACC_FINAL, "packageId", "I", null, packageId)
            cw.visitEnd()
            jarCreator.addEntry(
                internalName + SdkConstants.DOT_CLASS,
                cw.toByteArray().inputStream()
            )
        }
    }
}

/**
 * Processes the symbol table and generates necessary files: R.txt, R.java. Afterwards generates
 * `R.java` or `R.jar` for all libraries the main library depends on.
 *
 * @param librarySymbols table with symbols of resources for the library.
 * @param depSymbolTables symbol tables of the libraries which this library depends on
 * @param mainPackageName package name of this library
 * @param manifestFile manifest file
 * @param sourceOut directory to contain R.java
 * @param rClassOutputJar file to output R.jar.
 * @param symbolFileOut R.txt file location
 * @param nonTransitiveRClass if true, the generated R class for this library and the  R.txt will
 *                         contain only the resources defined in this library, otherwise they will
 *                         contain all the resources merged from the transitive dependencies.
 */
@Throws(IOException::class)
fun processLibraryMainSymbolTable(
    librarySymbols: SymbolTable,
    depSymbolTables: List<SymbolTable>,
    mainPackageName: String?,
    manifestFile: File,
    rClassOutputJar: File?,
    symbolFileOut: File?,
    platformSymbols: SymbolTable,
    nonTransitiveRClass: Boolean,
    generateDependencyRClasses: Boolean,
    idProvider: IdProvider
) {

    // Parse the manifest only when necessary.
    val finalPackageName = mainPackageName ?: getPackageNameFromManifest(parseManifest(manifestFile))

    // Get symbol tables of the libraries we depend on.
    val tablesToWrite =
        processLibraryMainSymbolTable(
            finalPackageName,
            librarySymbols,
            depSymbolTables,
            platformSymbols,
            nonTransitiveRClass,
            symbolFileOut?.toPath(),
            generateDependencyRClasses,
            idProvider
        )

    if (rClassOutputJar != null) {
        GFileUtils.deleteIfExists(rClassOutputJar)
        exportToCompiledJava(tablesToWrite, rClassOutputJar.toPath())
    }
}

@VisibleForTesting
internal fun processLibraryMainSymbolTable(
    finalPackageName: String,
    librarySymbols: SymbolTable,
    depSymbolTables: List<SymbolTable>,
    platformSymbols: SymbolTable,
    nonTransitiveRClass: Boolean,
    symbolFileOut: Path?,
    generateDependencyRClasses: Boolean = true,
    idProvider: IdProvider = IdProvider.sequential()
): List<SymbolTable> {
    // Merge all the symbols together.
    // We have to rewrite the IDs because some published R.txt inside AARs are using the
    // wrong value for some types, and we need to ensure there is no collision in the
    // file we are creating.
    val allSymbols: SymbolTable = mergeAndRenumberSymbols(
        finalPackageName, librarySymbols, depSymbolTables, platformSymbols, idProvider
    )

    val mainSymbolTable = if (nonTransitiveRClass) allSymbols.filter(librarySymbols) else allSymbols

    // Generate R.txt file.
    symbolFileOut?.let {
        Files.createDirectories(it.parent)
        SymbolIo.writeForAar(mainSymbolTable, it)
    }

    return if (generateDependencyRClasses) {
        RGeneration.generateAllSymbolTablesToWrite(allSymbols, mainSymbolTable, depSymbolTables)
    } else {
        ImmutableList.of(mainSymbolTable)
    }
}


fun writeSymbolListWithPackageName(table: SymbolTable, writer: Writer) {
    writer.write(table.tablePackage)
    writer.write('\n'.code)

    for (resourceType in ResourceType.values()) {
        val symbols = table.getSymbolByResourceType(resourceType)
        for (symbol in symbols) {
            writer.write(resourceType.getName())
            writer.write(' '.code)
            writer.write(symbol.canonicalName)
            if (symbol is Symbol.StyleableSymbol) {
                for (child in symbol.children) {
                    writer.write(' '.code)
                    writer.write(child)
                }
            }
            writer.write('\n'.code)
        }
    }
}

/**
 * Writes the symbol table treating all symbols as public in the AAR R.txt format.
 *
 * See [SymbolIo.readFromPublicTxtFile] for the reading counterpart.
 *
 * The format does not include styleable children (see `SymbolExportUtilsTest`)
 */
fun writePublicTxtFile(table: SymbolTable, writer: Writer) {
    for (resType in ResourceType.values()) {
        val symbols =
            table.getSymbolByResourceType(resType)
        for (s in symbols) {
            writer.write(s.resourceType.getName())
            writer.write(' '.code)
            writer.write(s.canonicalName)
            writer.write('\n'.code)
        }
    }
}
