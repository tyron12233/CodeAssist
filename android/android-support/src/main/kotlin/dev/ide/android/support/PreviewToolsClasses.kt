package dev.ide.android.support

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Classes the real-view preview runs inside the project's own classpath to show `tools:listitem` in a
 * RecyclerView. RecyclerView comes from the project's jars, at the project's version, so the adapter that fills
 * it must be compiled against that API rather than the IDE's: it is emitted here as bytecode and interpreted
 * alongside the library, like the preview's `R` classes.
 *
 * `ToolsList.install(RecyclerView rv, int layoutId, int count)` gives a RecyclerView without a layout manager a
 * vertical `LinearLayoutManager` (as Android Studio does) and an adapter that inflates `layoutId` [count] times.
 */
object PreviewToolsClasses {

    const val VERSION = "1"
    const val INSTALLER = "dev.ide.preview.tools.ToolsList"

    private const val LIST = "dev/ide/preview/tools/ToolsList"
    private const val ADAPTER = "dev/ide/preview/tools/ToolsList\$Adapter"
    private const val HOLDER = "dev/ide/preview/tools/ToolsList\$Holder"
    private const val RV = "androidx/recyclerview/widget/RecyclerView"
    private const val RV_ADAPTER = "$RV\$Adapter"
    private const val RV_HOLDER = "$RV\$ViewHolder"
    private const val RV_LM = "$RV\$LayoutManager"
    private const val LINEAR_LM = "androidx/recyclerview/widget/LinearLayoutManager"

    /** The classes as a jar under [dir], written once per [VERSION]. */
    fun jar(dir: Path): Path {
        val out = dir.resolve("preview-tools-v$VERSION.jar")
        if (Files.isRegularFile(out)) return out
        Files.createDirectories(dir)
        val tmp = dir.resolve("preview-tools-v$VERSION.jar.tmp")
        java.util.jar.JarOutputStream(Files.newOutputStream(tmp)).use { jos ->
            for ((name, bytes) in listOf(LIST to list(), ADAPTER to adapter(), HOLDER to holder())) {
                jos.putNextEntry(java.util.jar.JarEntry("$name.class")); jos.write(bytes); jos.closeEntry()
            }
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING)
        return out
    }

    // Class version 49: no stack map frames are needed for install's one branch.
    private fun writer(name: String, superName: String) = ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
        visit(V1_5, ACC_PUBLIC or ACC_SUPER, name, null, superName, null)
    }

    private fun list(): ByteArray = writer(LIST, "java/lang/Object").run {
        visitMethod(ACC_PUBLIC or ACC_STATIC, "install", "(L$RV;II)V", null, null).apply {
            visitCode()
            val hasManager = Label()
            visitVarInsn(ALOAD, 0)
            visitMethodInsn(INVOKEVIRTUAL, RV, "getLayoutManager", "()L$RV_LM;", false)
            visitJumpInsn(IFNONNULL, hasManager)
            visitVarInsn(ALOAD, 0)
            visitTypeInsn(NEW, LINEAR_LM)
            visitInsn(DUP)
            visitVarInsn(ALOAD, 0)
            visitMethodInsn(INVOKEVIRTUAL, RV, "getContext", "()Landroid/content/Context;", false)
            visitMethodInsn(INVOKESPECIAL, LINEAR_LM, "<init>", "(Landroid/content/Context;)V", false)
            visitMethodInsn(INVOKEVIRTUAL, RV, "setLayoutManager", "(L$RV_LM;)V", false)
            visitLabel(hasManager)
            visitVarInsn(ALOAD, 0)
            visitTypeInsn(NEW, ADAPTER)
            visitInsn(DUP)
            visitVarInsn(ILOAD, 1)
            visitVarInsn(ILOAD, 2)
            visitMethodInsn(INVOKESPECIAL, ADAPTER, "<init>", "(II)V", false)
            visitMethodInsn(INVOKEVIRTUAL, RV, "setAdapter", "(L$RV_ADAPTER;)V", false)
            visitInsn(RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        visitEnd()
        toByteArray()
    }

    private fun adapter(): ByteArray = writer(ADAPTER, RV_ADAPTER).run {
        visitField(ACC_PRIVATE or ACC_FINAL, "layout", "I", null, null).visitEnd()
        visitField(ACC_PRIVATE or ACC_FINAL, "count", "I", null, null).visitEnd()
        visitMethod(ACC_PUBLIC, "<init>", "(II)V", null, null).apply {
            visitCode()
            visitVarInsn(ALOAD, 0)
            visitMethodInsn(INVOKESPECIAL, RV_ADAPTER, "<init>", "()V", false)
            visitVarInsn(ALOAD, 0); visitVarInsn(ILOAD, 1); visitFieldInsn(PUTFIELD, ADAPTER, "layout", "I")
            visitVarInsn(ALOAD, 0); visitVarInsn(ILOAD, 2); visitFieldInsn(PUTFIELD, ADAPTER, "count", "I")
            visitInsn(RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        visitMethod(ACC_PUBLIC, "getItemCount", "()I", null, null).apply {
            visitCode()
            visitVarInsn(ALOAD, 0); visitFieldInsn(GETFIELD, ADAPTER, "count", "I")
            visitInsn(IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        // VH erases to ViewHolder, so these are the adapter's own (erased) signatures and need no bridges.
        visitMethod(ACC_PUBLIC, "onCreateViewHolder", "(Landroid/view/ViewGroup;I)L$RV_HOLDER;", null, null).apply {
            visitCode()
            visitTypeInsn(NEW, HOLDER)
            visitInsn(DUP)
            visitVarInsn(ALOAD, 1)
            visitMethodInsn(INVOKEVIRTUAL, "android/view/ViewGroup", "getContext", "()Landroid/content/Context;", false)
            visitMethodInsn(INVOKESTATIC, "android/view/LayoutInflater", "from", "(Landroid/content/Context;)Landroid/view/LayoutInflater;", false)
            visitVarInsn(ALOAD, 0); visitFieldInsn(GETFIELD, ADAPTER, "layout", "I")
            visitVarInsn(ALOAD, 1)
            visitInsn(ICONST_0)
            visitMethodInsn(INVOKEVIRTUAL, "android/view/LayoutInflater", "inflate", "(ILandroid/view/ViewGroup;Z)Landroid/view/View;", false)
            visitMethodInsn(INVOKESPECIAL, HOLDER, "<init>", "(Landroid/view/View;)V", false)
            visitInsn(ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        visitMethod(ACC_PUBLIC, "onBindViewHolder", "(L$RV_HOLDER;I)V", null, null).apply {
            visitCode()
            visitInsn(RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        visitEnd()
        toByteArray()
    }

    private fun holder(): ByteArray = writer(HOLDER, RV_HOLDER).run {
        visitMethod(ACC_PUBLIC, "<init>", "(Landroid/view/View;)V", null, null).apply {
            visitCode()
            visitVarInsn(ALOAD, 0); visitVarInsn(ALOAD, 1)
            visitMethodInsn(INVOKESPECIAL, RV_HOLDER, "<init>", "(Landroid/view/View;)V", false)
            visitInsn(RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        visitEnd()
        toByteArray()
    }
}
