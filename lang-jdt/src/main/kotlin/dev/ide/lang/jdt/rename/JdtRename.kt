package dev.ide.lang.jdt.rename

import dev.ide.lang.dom.TextRange
import dev.ide.lang.jdt.dom.JdtParsedFile
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.IBinding
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.NodeFinder
import org.eclipse.jdt.core.dom.SimpleName

/**
 * The symbol a rename is about, distilled from JDT bindings into something stable enough to match across
 * separately-parsed compilation units. [bindingKey] is JDT's cross-unit binding key (a `Lpkg/Type;`-style
 * identifier); [isType] turns on the constructor special-case (a class rename must also retouch constructor
 * declaration name tokens, whose own binding is the constructor, not the type); [fileLocal] marks symbols
 * (locals, parameters, type parameters) whose references can only live in the declaring file, so the
 * orchestrator skips the project-wide sweep.
 */
data class RenameTarget(
    val oldName: String,
    val kind: String,
    val bindingKey: String,
    val fileLocal: Boolean,
    val isType: Boolean,
    val declRange: TextRange,
)

/** Find the renameable symbol under the caret and all of its references within a parsed file. */
object JdtRename {

    /** The renameable symbol whose name token contains [offset], or null if the caret isn't on one. */
    fun targetAt(parsed: JdtParsedFile, offset: Int): RenameTarget? {
        val found = NodeFinder.perform(parsed.cu, offset.coerceIn(0, parsed.text().length), 0) ?: return null
        val name = found as? SimpleName ?: found.parent as? SimpleName ?: return null
        val binding = name.resolveBinding() ?: return null
        val d = describe(binding) ?: return null
        return RenameTarget(name.identifier, d.kind, d.key, d.fileLocal, d.isType,
            TextRange(name.startPosition, name.startPosition + name.length))
    }

    private data class Described(val key: String, val kind: String, val fileLocal: Boolean, val isType: Boolean)

    /** Map a binding to its rename identity. A constructor renames the *class*; an override is not chased. */
    private fun describe(b: IBinding): Described? = when (b) {
        is ITypeBinding -> when {
            b.isTypeVariable -> b.key?.let { Described(it, "type parameter", fileLocal = true, isType = false) }
            b.isAnonymous || b.isWildcardType || b.isCapture || b.isArray -> null
            else -> b.typeDeclaration.key?.let { Described(it, typeKindLabel(b), fileLocal = false, isType = true) }
        }
        is IMethodBinding ->
            if (b.isConstructor) b.declaringClass?.typeDeclaration?.key?.let { Described(it, "class", fileLocal = false, isType = true) }
            else b.methodDeclaration.key?.let { Described(it, "method", fileLocal = false, isType = false) }
        is IVariableBinding -> when {
            b.isField -> b.variableDeclaration.key?.let { Described(it, "field", fileLocal = false, isType = false) }
            b.isParameter -> b.key?.let { Described(it, "parameter", fileLocal = true, isType = false) }
            else -> b.key?.let { Described(it, "local variable", fileLocal = true, isType = false) }
        }
        else -> null
    }

    private fun typeKindLabel(b: ITypeBinding): String = when {
        b.isInterface -> "interface"
        b.isEnum -> "enum"
        b.isRecord -> "record"
        b.isAnnotation -> "annotation"
        else -> "class"
    }

    /**
     * Every identifier token in [parsed] that refers to [target], as source ranges. Matches on the binding
     * key (so qualified uses, imports and the declaration are all caught), with a guard that the token text
     * equals the old name. For a type, constructor declaration names (`Foo() {}`) are matched too — their
     * binding is the constructor, whose declaring class is the renamed type.
     */
    fun referencesIn(parsed: JdtParsedFile, target: RenameTarget): List<TextRange> {
        val out = ArrayList<TextRange>()
        parsed.cu.accept(object : ASTVisitor() {
            override fun visit(node: SimpleName): Boolean {
                if (node.identifier == target.oldName && matches(node.resolveBinding(), target)) {
                    out += TextRange(node.startPosition, node.startPosition + node.length)
                }
                return true
            }
        })
        return out.distinctBy { it.start }.sortedBy { it.start }
    }

    private fun matches(binding: IBinding?, target: RenameTarget): Boolean = when (binding) {
        null -> false
        is ITypeBinding ->
            (if (binding.isTypeVariable) binding.key else binding.typeDeclaration.key) == target.bindingKey
        is IMethodBinding ->
            if (binding.isConstructor) target.isType && binding.declaringClass?.typeDeclaration?.key == target.bindingKey
            else binding.methodDeclaration.key == target.bindingKey
        is IVariableBinding ->
            (if (binding.isField) binding.variableDeclaration.key else binding.key) == target.bindingKey
        else -> false
    }
}
