package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtAnnotationEntry
import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtConstantExpression
import dev.ide.kotlin.syntax.psi.KtDestructuringDeclarationEntry
import dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtPrefixExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.kotlin.parse.unwrapParen
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.symbols.KotlinSymbol

/**
 * The built-in number types an expression made of number literals, arithmetic on them, explicit `toX()`
 * conversions and names of plainly typed numbers can take. Read off the tree, not inferred, so a mismatch
 * against it is a fact about the code.
 *
 * Kotlin types an unsuffixed integer literal by what is expected of it, among the integer types its value
 * fits. Arithmetic on two such literals (`1 + 1`, `100 and 3`) only ever yields an `Int` or a `Long`, never a
 * `Byte`, `Short` or floating type; arithmetic with a typed operand follows the operator overloads (`Long`
 * wins over `Int`, `Byte` and `Short` widen to `Int`, a floating operand wins over both).
 */
internal class NumericConst(
    /** The numeric types the expression may take, as `kotlin.Int` etc. */
    val types: Set<String>,
    /** An integer literal, or arithmetic over integer literals only: its type is still open. */
    val literal: Boolean,
) {
    /** The type the expression gets where nothing is expected of it. */
    val default: String get() = if (INT in types) INT else if (LONG in types && types.size == 1) LONG else types.first()
}

private const val INT = "kotlin.Int"
private const val LONG = "kotlin.Long"
private const val SHORT = "kotlin.Short"
private const val BYTE = "kotlin.Byte"
private const val DOUBLE = "kotlin.Double"
private const val FLOAT = "kotlin.Float"

private val ARITHMETIC_TOKENS = setOf(KtTokens.PLUS, KtTokens.MINUS, KtTokens.MUL, KtTokens.DIV, KtTokens.PERC)
private val BITWISE_INFIX = setOf("and", "or", "xor")
private val SHIFT_INFIX = setOf("shl", "shr", "ushr")
private val CONVERSIONS = mapOf(
    "toInt" to INT, "toLong" to LONG, "toShort" to SHORT, "toByte" to BYTE, "toDouble" to DOUBLE, "toFloat" to FLOAT,
)
/** Members of `Int` whose call on literals is still "arithmetic over literals". */
private val LITERAL_MEMBER_OPS = setOf(
    "plus", "minus", "times", "div", "rem", "inv", "unaryMinus", "unaryPlus", "inc", "dec",
    "and", "or", "xor", "shl", "shr", "ushr",
)
private val NUMERIC_TYPE_TEXT = mapOf(
    "Int" to INT, "Long" to LONG, "Short" to SHORT, "Byte" to BYTE, "Double" to DOUBLE, "Float" to FLOAT,
)

private fun exact(t: String) = NumericConst(setOf(t), literal = false)
private val LITERAL_ARITHMETIC = NumericConst(setOf(INT, LONG), literal = true)

/** The numeric types [e] can take, or null when it is not an expression this reads. */
internal fun KotlinSemanticChecks.numericConst(e0: KtExpression, resolver: KotlinResolver, depth: Int = 0): NumericConst? {
    if (depth > 12) return null
    val e = unwrapParen(e0)
    return when (e) {
        is KtConstantExpression -> literalConst(e.text, negative = false)
        is KtPrefixExpression -> {
            if (e.operationToken != KtTokens.MINUS && e.operationToken != KtTokens.PLUS) return null
            val base = e.baseExpression?.let { unwrapParen(it) } ?: return null
            if (base is KtConstantExpression) return literalConst(base.text, negative = e.operationToken == KtTokens.MINUS)
            val b = numericConst(base, resolver, depth + 1) ?: return null
            if (b.literal) return null
            // `unaryMinus`/`unaryPlus` on a Byte or a Short give an Int.
            exact(if (b.default == BYTE || b.default == SHORT) INT else b.default)
        }
        is KtBinaryExpression -> {
            val token = e.operationToken
            val infix = if (token == KtTokens.IDENTIFIER) e.operationReference.getReferencedName() else null
            if (token !in ARITHMETIC_TOKENS && infix !in BITWISE_INFIX && infix !in SHIFT_INFIX) return null
            val l = numericConst(e.left ?: return null, resolver, depth + 1) ?: return null
            val r = numericConst(e.right ?: return null, resolver, depth + 1) ?: return null
            combine(l, r, infix)
        }
        is KtDotQualifiedExpression -> {
            val sel = e.selectorExpression as? KtCallExpression ?: return null
            val name = (sel.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
            if (sel.typeArgumentList != null || sel.lambdaArguments.isNotEmpty()) return null
            val recv = numericConst(e.receiverExpression, resolver, depth + 1)
            CONVERSIONS[name]?.let { target ->
                if (sel.valueArguments.isNotEmpty()) return null
                // A number's (or a String's, or a Char's) own conversion; on anything else it may be a user's.
                if (recv != null) return exact(target)
                val rt = resolver.inferType(e.receiverExpression) ?: return null
                return if (!rt.isTypeParameter && rt.qualifiedName in CONVERTIBLE_RECEIVERS) exact(target) else null
            }
            if (recv == null || !recv.literal || name !in LITERAL_MEMBER_OPS) return null
            val argsLiteral = sel.valueArguments.all { a ->
                a.getArgumentName() == null && a.getArgumentExpression()?.let { numericConst(it, resolver, depth + 1)?.literal } == true
            }
            if (argsLiteral && sel.valueArguments.size <= 1) LITERAL_ARITHMETIC else null
        }
        is KtNameReferenceExpression -> namedNumber(e, resolver, depth)
        else -> null
    }
}

private val CONVERTIBLE_RECEIVERS = setOf(INT, LONG, SHORT, BYTE, DOUBLE, FLOAT, "kotlin.String", "kotlin.Char")

private fun combine(l: NumericConst, r: NumericConst, infix: String?): NumericConst? {
    if (l.literal && r.literal && INT in l.types && INT in r.types) return LITERAL_ARITHMETIC
    val a = l.default
    val b = r.default
    return when (infix) {
        null -> exact(
            when {
                a == DOUBLE || b == DOUBLE -> DOUBLE
                a == FLOAT || b == FLOAT -> FLOAT
                a == LONG || b == LONG -> LONG
                else -> INT
            },
        )
        // `and`/`or`/`xor` take the same type on both sides; a literal side becomes the other's.
        in BITWISE_INFIX -> when {
            !l.literal && !r.literal -> if (a == b && (a == INT || a == LONG)) exact(a) else null
            l.literal && (b == INT || b == LONG) -> exact(b)
            r.literal && (a == INT || a == LONG) -> exact(a)
            else -> null
        }
        else -> if ((a == INT || a == LONG) && !l.literal && b == INT) exact(a) else null
    }
}

/** An integer or floating literal's types; [negative] when a unary minus is applied to it. */
private fun literalConst(raw: String, negative: Boolean): NumericConst? {
    val text = raw.trim().replace("_", "")
    if (text.isEmpty() || !(text[0].isDigit() || text[0] == '.')) return null // a char, string, boolean or null
    val lower = text.lowercase()
    val hex = lower.startsWith("0x")
    val bin = lower.startsWith("0b")
    if (lower.contains('u')) return null // an unsigned literal
    if (!hex && !bin) {
        if (lower.endsWith("f")) return exact(FLOAT)
        if ('.' in lower || 'e' in lower) return exact(DOUBLE)
    }
    if (lower.endsWith("l")) return exact(LONG)
    val digits = if (hex || bin) lower.substring(2) else lower
    val magnitude = digits.toLongOrNull(if (hex) 16 else if (bin) 2 else 10) ?: return null
    if (magnitude > Int.MAX_VALUE) {
        // Past Int it is a Long; right at the edge the sign decides, which is not worth modelling.
        return if (negative && magnitude <= Int.MAX_VALUE.toLong() + 1) null else exact(LONG)
    }
    val v = if (negative) -magnitude else magnitude
    val types = LinkedHashSet<String>()
    if (v in Byte.MIN_VALUE..Byte.MAX_VALUE) types += BYTE
    if (v in Short.MIN_VALUE..Short.MAX_VALUE) types += SHORT
    types += INT
    types += LONG
    return NumericConst(types, literal = true)
}

/**
 * The number a plain name denotes: a local, parameter or same-file top-level property whose type is written
 * as a built-in number, or that is initialized with a number expression and leaves its type to it.
 */
private fun KotlinSemanticChecks.namedNumber(ref: KtNameReferenceExpression, resolver: KotlinResolver, depth: Int): NumericConst? {
    val name = ref.getReferencedName()
    val decl = nearestLocalDecl(name, ref.textRange.startOffset, ref) ?: topLevelProperty(ref, name) ?: return null
    val typeRef: KtTypeReference? = when (decl) {
        is KtProperty -> decl.typeReference
        // A `vararg x: Int` is an `IntArray` inside its function.
        is KtParameter -> if (decl.isVarArg) return null else decl.typeReference
        else -> return null
    }
    if (typeRef != null) {
        val text = typeRef.text.trim().removePrefix("kotlin.")
        // A nullable number may have been smart-cast or tested; leave it.
        return NUMERIC_TYPE_TEXT[text]?.let(::exact)
    }
    val prop = decl as? KtProperty ?: return null
    if (prop.hasDelegate()) return null
    val init = prop.initializer ?: return null
    val c = numericConst(init, resolver, depth + 1) ?: return null
    return exact(c.default)
}

/** The same file's top-level property [name], when [ref] is outside any class and nothing else in the file
 *  declares that name (so no local, parameter or member could be the one it means). */
private fun topLevelProperty(ref: KtNameReferenceExpression, name: String): KtProperty? {
    // A class member, or the receiver of an extension or of a lambda, would be found before the file's own.
    var p: KtElement? = ref.parent as? KtElement
    while (p != null && p !is KtFile) {
        if (p is KtClassOrObject || p is KtFunctionLiteral) return null
        if (p is KtNamedFunction && p.receiverTypeReference != null) return null
        if (p is KtProperty && p.receiverTypeReference != null) return null
        p = p.parent as? KtElement
    }
    val file = p as? KtFile ?: return null
    // An explicit import of the name wins over the file's package.
    if (file.importDirectives.any { (it.aliasName ?: it.importedFqName?.shortName()?.asString()) == name }) return null
    val prop = file.declarations.filterIsInstance<KtProperty>().singleOrNull { it.name == name } ?: return null
    var others = 0
    fun rec(e: KtElement) {
        if (others > 0) return
        if (e !== prop && (e is KtProperty || e is KtParameter || e is KtDestructuringDeclarationEntry || e is KtNamedFunction) &&
            (e as KtNamedDeclaration).name == name
        ) { others++; return }
        var c = e.firstChild
        while (c != null && others == 0) { (c as? KtElement)?.let(::rec); c = c.nextSibling }
    }
    rec(file)
    return if (others == 0) prop else null
}

/**
 * Whether parameter [index] of [callee] is declared in this project's source with a built-in number type
 * written out (`b: Byte`, `vararg s: Short`), so the number it takes is a fact of the declaration, not a type
 * argument substituted on the way in.
 */
internal fun plainNumericSourceParameter(callee: KotlinSymbol, index: Int): String? {
    if (!callee.origin.fromSource) return null
    val fn = (callee.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtNamedFunction ?: return null
    if (fn.typeParameters.isNotEmpty()) return null
    val text = fn.valueParameters.getOrNull(index)?.typeReference?.text?.trim()?.removePrefix("kotlin.") ?: return null
    return NUMERIC_TYPE_TEXT[text]
}

private val COMPOUND_ARITHMETIC = setOf(KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ)

/**
 * `x += v` on a local number variable whose result is a wider number than `x` (`x += 1L` for an `Int`,
 * `s += 1` for a `Short`): the built-in numbers have no `plusAssign`, so it is `x = x + v`, and the sum
 * does not fit back.
 */
internal fun KotlinSemanticChecks.numericCompoundAssignment(e: KtBinaryExpression, resolver: KotlinResolver): Diagnostic? {
    if (e.operationToken !in COMPOUND_ARITHMETIC) return null
    val left = e.left?.let { unwrapParen(it) } as? KtNameReferenceExpression ?: return null
    val decl = nearestLocalDecl(left.getReferencedName(), left.textRange.startOffset, left) as? KtProperty ?: return null
    if (!decl.isVar) return null
    val target = namedNumber(left, resolver, 0)?.default ?: return null
    val value = numericConst(e.right ?: return null, resolver) ?: return null
    val result = combine(exact(target), value, null)?.default ?: return null
    if (result == target) return null
    val r = e.operationReference.textRange
    return Diagnostic(
        dev.ide.lang.dom.TextRange(r.startOffset, r.endOffset), dev.ide.lang.dom.Severity.ERROR,
        "Type mismatch: inferred type is ${result.substringAfterLast('.')} but ${target.substringAfterLast('.')} was expected",
        KotlinDiagnosticCodes.TYPE_MISMATCH,
    )
}

/**
 * Arguments of an annotation declared in this file whose number expression cannot take the built-in number
 * type written on the parameter (`@Ann(128)` for a `Byte`, `@Ann(1 + 1)` for a `Short`).
 */
internal fun KotlinSemanticChecks.annotationNumericArguments(entry: KtAnnotationEntry, resolver: KotlinResolver): List<Diagnostic> {
    val name = entry.shortName?.asString() ?: return emptyList()
    if (entry.typeReference?.text?.contains('.') == true) return emptyList()
    val file = entry.containingFile as? KtFile ?: return emptyList()
    if (file.importDirectives.any { (it.aliasName ?: it.importedFqName?.shortName()?.asString()) == name }) return emptyList()
    val cls = file.declarations.filterIsInstance<KtClass>().singleOrNull { it.name == name }
        ?.takeIf { it.isAnnotation() } ?: return emptyList()
    val params = cls.primaryConstructorParameters
    if (params.any { it.isVarArg }) return emptyList()
    val out = ArrayList<Diagnostic>()
    var sawNamed = false
    entry.valueArguments.forEachIndexed { i, arg ->
        val named = arg.getArgumentName()?.asName?.identifier
        if (named != null) sawNamed = true else if (sawNamed) return out // positional after named: not this check's call
        val param = (if (named != null) params.firstOrNull { it.name == named } else params.getOrNull(i)) ?: return@forEachIndexed
        val target = param.typeReference?.text?.trim()?.removePrefix("kotlin.")?.let { NUMERIC_TYPE_TEXT[it] } ?: return@forEachIndexed
        val value = arg.getArgumentExpression() ?: return@forEachIndexed
        val c = numericConst(value, resolver) ?: return@forEachIndexed
        if (target in c.types) return@forEachIndexed
        val declared = service.typeByFqn(target) ?: return@forEachIndexed
        val actual = service.typeByFqn(c.default) ?: return@forEachIndexed
        val r = value.textRange
        out += mismatchDiagnostic(r.startOffset, r.endOffset, actual, declared)
    }
    return out
}
