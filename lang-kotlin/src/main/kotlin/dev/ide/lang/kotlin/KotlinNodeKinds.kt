package dev.ide.lang.kotlin

import dev.ide.lang.dom.NodeKind

/**
 * The Kotlin [NodeKind]s used by the neutral-DOM adapter.
 *
 * Where a Kotlin construct lines up with a language-neutral concept the DOM already names, the shared
 * [NodeKind] constant ([NodeKind.METHOD_CALL], [NodeKind.NAME_REF], etc.) is reused so cross-language editor
 * features (navigation, refactor, the block editor) see one vocabulary. Kotlin-specific shapes that have
 * no neutral analogue get a `kt.*`-prefixed kind. The completion engine keys off these to classify the
 * caret position, so the mapping must be stable.
 */
object KotlinNodeKinds {
    // --- reused neutral kinds (KtElement -> the shared concept) ---
    val COMPILATION_UNIT = NodeKind.COMPILATION_UNIT   // KtFile
    val PACKAGE_DECL = NodeKind.PACKAGE_DECL           // KtPackageDirective
    val IMPORT_DECL = NodeKind.IMPORT_DECL             // KtImportDirective (explicit / alias / star)
    val CLASS_DECL = NodeKind.CLASS_DECL               // KtClassOrObject (class/object/interface/enum/companion)
    val METHOD_DECL = NodeKind.METHOD_DECL             // KtNamedFunction (receiver type ref present => extension)
    val PARAMETER = NodeKind.PARAMETER                 // KtParameter
    val BLOCK = NodeKind.BLOCK                         // KtBlockExpression
    val METHOD_CALL = NodeKind.METHOD_CALL             // KtCallExpression
    val MEMBER_ACCESS = NodeKind.MEMBER_ACCESS         // KtDotQualifiedExpression (selector is the completion site)
    val NAME_REF = NodeKind.NAME_REF                   // KtNameReferenceExpression (scope completion site)
    val TYPE_REF = NodeKind.TYPE_REF                   // KtTypeReference (type-position completion site)
    val LITERAL = NodeKind.LITERAL                     // KtConstantExpression / KtStringTemplateExpression
    val LOCAL_VAR = NodeKind.LOCAL_VAR                 // KtProperty in a block (local val/var)
    val ERROR = NodeKind.ERROR                         // PsiErrorElement — preserves error tolerance
    val MISSING = NodeKind.MISSING

    // --- Kotlin-specific kinds (no neutral analogue) ---
    /** A top-level or member `val`/`var` (as opposed to a local — that stays [LOCAL_VAR]). */
    val PROPERTY = NodeKind("kt.property")

    /** `a?.b` — a safe call. Same member set as `.`; classified separately so completion can keep `?.` shape. */
    val SAFE_ACCESS = NodeKind("kt.safe_access")

    /** `a?.b.c` style; the qualified chain wrapper when not a plain dot. */
    val QUALIFIED = NodeKind("kt.qualified")

    /** `typealias X = Y`. */
    val TYPEALIAS = NodeKind("kt.typealias")

    /** `object`/`companion object` body distinct from a plain class (still tagged [CLASS_DECL] for the decl). */
    val OBJECT_DECL = NodeKind("kt.object")

    /** Lambda `{ x -> … }`. */
    val LAMBDA = NodeKind("kt.lambda")

    /** `when (…) { … }`. */
    val WHEN = NodeKind("kt.when")

    /** String template `"… $x …"` (its interpolated entries resolve as expressions). */
    val STRING_TEMPLATE = NodeKind("kt.string_template")

    /** Binary / infix expression `a + b`, `a to b`. */
    val BINARY = NodeKind("kt.binary")

    /** Constructor `constructor(...)` / the primary constructor. */
    val CONSTRUCTOR = NodeKind("kt.constructor")

    /** Any adapted KtElement without a more specific kind; keeps the tree total. */
    val OTHER = NodeKind("kt.element")
}
