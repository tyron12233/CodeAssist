package dev.ide.kotlin.syntax

import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.kotlin.syntax.psi.collectDescendantsOfType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [KtNamedDeclaration.hasQualifiedName] answers "is [KtNamedDeclaration.fqName] non-null" from the tree's shape,
 * without building the name. It must give the same answer as building it, for every declaration in a corpus
 * that covers local, anonymous, nested, companion and script declarations and half-typed code.
 */
class QualifiedNameShapeTest {

    @Test
    fun theShapeAnswerAgreesWithBuildingTheName() {
        val root = File(System.getProperty("kotlinSyntax.corpusRoot")!!)
        val files = (File(root, "experimental/kotlin-syntax/testData/kotlin-psi").walkTopDown() +
            File(root, "lang/lang-kotlin/src/commonMain").walkTopDown())
            .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }.sortedBy { it.path }.toList()
        val diverged = ArrayList<String>()
        var checked = 0
        var unqualified = 0
        for (file in files) {
            val text = file.readText().replace("\r\n", "\n")
            val isScript = file.extension == "kts"
            // Two parses, so the shape answer is never served from a cached fqName.
            val byShape = KotlinSyntax.parseFile(text, isScript).collectDescendantsOfType<KtNamedDeclaration>()
                .map { it.hasQualifiedName }
            val byName = KotlinSyntax.parseFile(text, isScript).collectDescendantsOfType<KtNamedDeclaration>()
            for ((i, decl) in byName.withIndex()) {
                checked++
                val qualified = decl.fqName != null
                if (!qualified) unqualified++
                if (byShape[i] != qualified) diverged += "${file.name}: ${decl.name} at ${decl.textRange} shape=${byShape[i]}"
            }
        }
        println("qualified-name shape: $checked declarations ($unqualified without a qualified name), ${diverged.size} diverged")
        assertTrue(checked > 10_000 && unqualified > 1_000, "the corpus should exercise both answers")
        assertTrue(diverged.isEmpty(), diverged.take(10).joinToString("\n"))
    }
}
