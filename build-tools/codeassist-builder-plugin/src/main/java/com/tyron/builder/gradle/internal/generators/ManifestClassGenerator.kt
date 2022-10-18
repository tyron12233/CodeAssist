package com.tyron.builder.gradle.internal.generators

import com.android.SdkConstants
import com.tyron.builder.compiling.GeneratedCodeFileCreator
import com.tyron.builder.packaging.JarFlinger
import com.android.ide.common.symbols.parseManifest
import com.google.common.collect.ImmutableList
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.TreeMap
import java.util.zip.Deflater

/** Creates a jar file containing a compiled manifest.class file based on the properties provided
 * a [ManifestClassData] instance.
 */
class ManifestClassGenerator(private val manifestClassData: ManifestClassData) :
    GeneratedCodeFileCreator {

    override val folderPath: File
        get() = manifestClassData.outputFilePath.parentFile
    override val generatedFilePath: File
        get() = manifestClassData.outputFilePath

    val fullyQualifiedManifestClassName by lazy {
        if (manifestClassData.namespace.isEmpty()) "Manifest"
        else "${manifestClassData.namespace.replace('.', '/')}/Manifest"
    }

    val customPermissions by lazy { getCustomPermissions(manifestClassData.manifestFile) }

    override fun generate() = generateManifestJar(
        customPermissions,
        manifestClassData.outputFilePath
    )

    private fun generateManifestJar(permissions: List<String>, outputJar: File) {
        JarFlinger(outputJar.toPath()).use {
            it.setCompressionLevel(Deflater.NO_COMPRESSION)
            it.addEntry(
                "$fullyQualifiedManifestClassName\$permission${SdkConstants.DOT_CLASS}",
                generateManifestPermissionClass(permissions).inputStream()
            )
            it.addEntry(
                "$fullyQualifiedManifestClassName${SdkConstants.DOT_CLASS}",
                generateManifestClass().inputStream()
            )
        }
    }

    private fun generateManifestPermissionClass(permissions: List<String>): ByteArray {
        // Currently when permissions' names clash, AAPT2 chooses the LAST one to appear in the
        // manifest. For now copy this behaviour, but in the future we should use the full name to
        // avoid more clashes (however, if we normalise them to java names they might still clash, e.g.
        // "com.custom.permission" and "com_custom.permission").
        val permissionsMap = TreeMap<String, String>()
        permissions.forEach {
            // last one wins
            permissionsMap[getPermissionName(it)] = it
        }
        return ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
            visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC + Opcodes.ACC_FINAL,
                "$fullyQualifiedManifestClassName\$permission",
                null,
                "java/lang/Object",
                null
            )
            visitInnerClass(
                "$fullyQualifiedManifestClassName\$permission",
                fullyQualifiedManifestClassName,
                "permission",
                Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC + Opcodes.ACC_FINAL
            )
            // Sort to make sure it's deterministic.
            permissionsMap.forEach { permission ->
                visitField(
                    Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_STATIC,
                    permission.key,
                    "Ljava/lang/String;",
                    null,
                    permission.value
                ).visitEnd()
            }
            visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "java/lang/Object",
                    "<init>",
                    "()V",
                    false
                )
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
            visitEnd()
        }.toByteArray()
    }

    private fun generateManifestClass(): ByteArray {
        return ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
            visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_SUPER,
                fullyQualifiedManifestClassName,
                null,
                "java/lang/Object",
                null
            )
            visitInnerClass(
                "$fullyQualifiedManifestClassName\$permission",
                fullyQualifiedManifestClassName,
                "permission",
                Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC + Opcodes.ACC_FINAL
            )
            visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "java/lang/Object",
                    "<init>",
                    "()V",
                    false
                )
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
            visitEnd()
        }.toByteArray()
    }

    private fun getCustomPermissions(manifest: File): ImmutableList<String> {
        return parseManifest(manifest).customPermissions
    }
}

fun getPermissionName(permission: String) = permission.substringAfterLast('.', permission)
