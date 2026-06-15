package dev.ide.lang.jdt

import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.SimpleType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/** De-risks JDT standalone: tolerant parse of broken code, with cross-file + platform bindings. */
class JdtProbeTest {

    @Test
    fun toleratesBrokenCodeAndResolvesCrossFileAndPlatform() {
        val dir = Files.createTempDirectory("jdt-probe")
        val coreDir = dir.resolve("core")
        write(coreDir.resolve("com/example/A.java"), "package com.example; public class A { public static String hi() { return \"x\"; } public int n; }")

        // Broken: incomplete member access `ok.` then a stray `;` (exactly the typing-mid-edit case).
        val main = "package com.example; public class Main { void m(){ A a = new A(); String ok = \"hi\"; ok. ; } }"

        val parser = ASTParser.newParser(AST.getJLSLatest())
        parser.setKind(ASTParser.K_COMPILATION_UNIT)
        parser.setCompilerOptions(JavaCore.getOptions().also { JavaCore.setComplianceOptions(JavaCore.VERSION_17, it) })
        parser.setUnitName("com/example/Main.java")
        parser.setEnvironment(arrayOf(), arrayOf(coreDir.toString()), null, true)
        parser.setResolveBindings(true)
        parser.setStatementsRecovery(true)
        parser.setBindingsRecovery(true)
        parser.setSource(main.toCharArray())

        val cu = parser.createAST(null) as CompilationUnit
        assertTrue(cu.types().isNotEmpty(), "AST should be recovered despite the broken statement")

        val resolvedTypes = mutableListOf<String>()
        cu.accept(object : ASTVisitor() {
            override fun visit(node: SimpleType): Boolean {
                node.resolveBinding()?.qualifiedName?.let { resolvedTypes.add(it) }
                return true
            }
        })
        println("PROBE resolvedTypes=$resolvedTypes")
        assertTrue("com.example.A" in resolvedTypes, "cross-file type A must resolve from sourcepath: $resolvedTypes")
        assertTrue("java.lang.String" in resolvedTypes, "platform type String must resolve: $resolvedTypes")

        dir.toFile().deleteRecursively()
    }

    private fun write(file: Path, content: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}
