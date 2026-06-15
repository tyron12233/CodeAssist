package dev.ide.lang.jdt.env

import org.eclipse.jdt.internal.compiler.env.ICompilationUnit

/** A JDT compiler source unit over in-memory or on-disk content, identified by its FQCN. */
class JdtSourceUnit(fqcn: String, private val contents: CharArray) : ICompilationUnit {
    private val pkg = fqcn.substringBeforeLast('.', "")
    private val mainType = fqcn.substringAfterLast('.')
    private val fileNameChars = (fqcn.replace('.', '/') + ".java").toCharArray()

    override fun getContents(): CharArray = contents
    override fun getMainTypeName(): CharArray = mainType.toCharArray()
    override fun getPackageName(): Array<CharArray> =
        if (pkg.isEmpty()) emptyArray() else pkg.split('.').map { it.toCharArray() }.toTypedArray()
    override fun getFileName(): CharArray = fileNameChars
    override fun ignoreOptionalProblems(): Boolean = false
}
