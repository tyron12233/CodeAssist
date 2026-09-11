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
 *
 * [OTHER] is the catch-all, and it is meant to stay nearly empty: a construct that reaches it cannot be
 * told from any other, which pushes its consumers into reading PSI. Every shape that appears in ordinary
 * source is named here instead.
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

    /**
     * One literal piece of a [STRING_TEMPLATE] — plain text or an escape (`\n`). A raw (`"""`) string is
     * cut into one of these per line, so counting them says nothing about interpolation; for that, look
     * for a [STRING_TEMPLATE_INTERPOLATION] child.
     */
    val STRING_TEMPLATE_ENTRY = NodeKind("kt.string_template_entry")

    /** An interpolation inside a [STRING_TEMPLATE]: `$x` or `${…}`. Its child is the embedded expression. */
    val STRING_TEMPLATE_INTERPOLATION = NodeKind("kt.string_template_interpolation")

    /** Binary / infix expression `a + b`, `a to b`. */
    val BINARY = NodeKind("kt.binary")

    /** Constructor `constructor(...)` / the primary constructor. */
    val CONSTRUCTOR = NodeKind("kt.constructor")

    // --- calls and arguments ---
    /** The `(…)` of a call: the parent of every [ARGUMENT]. */
    val ARGUMENT_LIST = NodeKind("kt.argument_list")

    /** One argument inside an [ARGUMENT_LIST]. A trailing lambda is a [LAMBDA_ARGUMENT] instead. */
    val ARGUMENT = NodeKind("kt.argument")

    /** The `name =` half of a named argument; its child is the [NAME_REF] for the parameter. */
    val ARGUMENT_NAME = NodeKind("kt.argument_name")

    /** A trailing lambda passed outside the parens: `list.map { … }`. */
    val LAMBDA_ARGUMENT = NodeKind("kt.lambda_argument")

    /**
     * The type being constructed in an annotation entry, a supertype call, or an enum entry: the `Foo`
     * of `Foo(1)`.
     */
    val CONSTRUCTOR_CALLEE = NodeKind("kt.constructor_callee")

    /** `: this(…)` / `: super(…)` on a secondary constructor. */
    val CONSTRUCTOR_DELEGATION_CALL = NodeKind("kt.constructor_delegation_call")

    /** The `this`/`super` word inside a [CONSTRUCTOR_DELEGATION_CALL]. */
    val CONSTRUCTOR_DELEGATION_REF = NodeKind("kt.constructor_delegation_ref")

    // --- types ---
    /** A named type inside a [TYPE_REF]: the `List<String>` of `val x: List<String>`. */
    val USER_TYPE = NodeKind("kt.user_type")

    /** `T?` — wraps the non-null [USER_TYPE]. */
    val NULLABLE_TYPE = NodeKind("kt.nullable_type")

    /** `(A) -> B`. */
    val FUNCTION_TYPE = NodeKind("kt.function_type")

    /** The receiver half of a [FUNCTION_TYPE]: the `A.` of `A.() -> B`. */
    val FUNCTION_TYPE_RECEIVER = NodeKind("kt.function_type_receiver")

    /** `context(a: A)` in front of a declaration. */
    val CONTEXT_RECEIVER_LIST = NodeKind("kt.context_receiver_list")

    /** The `where T : X, T : Y` clause of a generic declaration. */
    val TYPE_CONSTRAINT_LIST = NodeKind("kt.type_constraint_list")

    /** One `T : X` in a [TYPE_CONSTRAINT_LIST]. */
    val TYPE_CONSTRAINT = NodeKind("kt.type_constraint")

    /** The `<…>` of a generic call or type. */
    val TYPE_ARGUMENT_LIST = NodeKind("kt.type_argument_list")

    /** One argument in a [TYPE_ARGUMENT_LIST], variance modifier and all (`out T`, `*`). */
    val TYPE_PROJECTION = NodeKind("kt.type_projection")

    /** The `<…>` declaring a generic's parameters. */
    val TYPE_PARAMETER_LIST = NodeKind("kt.type_parameter_list")

    /** One declared type parameter: the `T : Comparable<T>` of `fun <T : Comparable<T>> f()`. */
    val TYPE_PARAMETER = NodeKind("kt.type_parameter")

    // --- operators and the remaining expression shapes ---
    /** The operator token itself (`+`, `==`, `=`, an infix name) inside a [BINARY]/[PREFIX]/[POSTFIX]. */
    val OPERATOR = NodeKind("kt.operator")

    /** `-x`, `!x`, `++x`. */
    val PREFIX = NodeKind("kt.prefix")

    /** `x++`, `x!!`. */
    val POSTFIX = NodeKind("kt.postfix")

    /** `(x)`. */
    val PARENTHESIZED = NodeKind("kt.parenthesized")

    /** `a[i]`. */
    val ARRAY_ACCESS = NodeKind("kt.array_access")

    /** `x is T` / `x !is T`. */
    val IS = NodeKind("kt.is")

    /** `x as T` / `x as? T`. */
    val AS = NodeKind("kt.as")

    /** `this` / `this@Label`. */
    val THIS = NodeKind("kt.this")

    /** `super` / `super@Label`. */
    val SUPER = NodeKind("kt.super")

    /** The `@Label` of a labeled `this`/`return`/`break`/`continue`, and of a label declaration. */
    val LABEL_REF = NodeKind("kt.label_ref")

    /** `::foo` / `Foo::bar`. */
    val CALLABLE_REFERENCE = NodeKind("kt.callable_reference")

    /** `Foo::class`. */
    val CLASS_LITERAL = NodeKind("kt.class_literal")

    /** `object : Base() { … }` used as an expression; its child is the [CLASS_DECL]. */
    val OBJECT_LITERAL = NodeKind("kt.object_literal")

    /** The `{ … }` body of a [LAMBDA] — the parameter list and the [BLOCK] hang off this, not the lambda. */
    val FUNCTION_LITERAL = NodeKind("kt.function_literal")

    /** `val (a, b) = pair`. */
    val DESTRUCTURING = NodeKind("kt.destructuring")

    /** One name bound by a [DESTRUCTURING] (it is not a [LOCAL_VAR] — there is no `val` of its own). */
    val DESTRUCTURING_ENTRY = NodeKind("kt.destructuring_entry")

    /** `@Ann expr` — an annotation applied to an expression; the expression is the last child. */
    val ANNOTATED_EXPRESSION = NodeKind("kt.annotated_expression")

    /** `label@ expr` — the loop or expression a `break`/`continue`/`return` can name. */
    val LABELED_EXPRESSION = NodeKind("kt.labeled_expression")

    /** `[1, 2]` — only legal in an annotation argument. */
    val COLLECTION_LITERAL = NodeKind("kt.collection_literal")

    /** The `$$` in front of a multi-dollar string template. */
    val STRING_INTERPOLATION_PREFIX = NodeKind("kt.string_interpolation_prefix")

    // --- control flow ---
    /** `if (…) … else …`. The branches are [CONTROL_BODY] nodes, the condition a [CONTAINER]. */
    val IF = NodeKind("kt.if")

    /** `for (x in xs) …`. */
    val FOR = NodeKind("kt.for")

    /** `while (…) …`. */
    val WHILE = NodeKind("kt.while")

    /** `do … while (…)`. */
    val DO_WHILE = NodeKind("kt.do_while")

    /** `return` / `return@label x`. */
    val RETURN = NodeKind("kt.return")

    /** `throw e`. */
    val THROW = NodeKind("kt.throw")

    /** `break` / `break@label`. */
    val BREAK = NodeKind("kt.break")

    /** `continue` / `continue@label`. */
    val CONTINUE = NodeKind("kt.continue")

    /** `try { … }`; the handlers are [CATCH]/[FINALLY] children. */
    val TRY = NodeKind("kt.try")

    /** `catch (e: E) { … }`. */
    val CATCH = NodeKind("kt.catch")

    /** `finally { … }`. */
    val FINALLY = NodeKind("kt.finally")

    /** One `cond -> result` arm of a [WHEN] (the `else` arm too). */
    val WHEN_ENTRY = NodeKind("kt.when_entry")

    /** A plain-expression condition in a [WHEN_ENTRY]. */
    val WHEN_CONDITION = NodeKind("kt.when_condition")

    /** An `is T` condition in a [WHEN_ENTRY]. */
    val WHEN_CONDITION_IS = NodeKind("kt.when_condition_is")

    /** An `in range` condition in a [WHEN_ENTRY]. */
    val WHEN_CONDITION_IN = NodeKind("kt.when_condition_in")

    /** The `if (…)` guard narrowing a [WHEN_ENTRY]. */
    val WHEN_ENTRY_GUARD = NodeKind("kt.when_entry_guard")

    /**
     * The wrapper around the body of an `if` branch or a loop. This is what separates a control-structure
     * body from a [LAMBDA] that happens to sit in the same place, so it is the node to test before
     * reaching for PSI.
     */
    val CONTROL_BODY = NodeKind("kt.control_body")

    /**
     * A structural wrapper the parser inserts with no shape of its own: an `if`/`while` condition, a
     * `for` loop range, a `catch` parameter. The body wrapper is a [CONTROL_BODY] instead.
     */
    val CONTAINER = NodeKind("kt.container")

    // --- declaration structure ---
    /** The `(…)` declaring a function's or constructor's parameters. */
    val PARAMETER_LIST = NodeKind("kt.parameter_list")

    /** The modifiers and annotations in front of a declaration. */
    val MODIFIER_LIST = NodeKind("kt.modifier_list")

    /** One `@Foo(…)` inside a [MODIFIER_LIST]. */
    val ANNOTATION_ENTRY = NodeKind("kt.annotation_entry")

    /** The `[A B]` bracket grouping several [ANNOTATION_ENTRY]s under one use-site target. */
    val ANNOTATION_GROUP = NodeKind("kt.annotation_group")

    /** The `field:` of `@field:Ann`. */
    val ANNOTATION_USE_SITE = NodeKind("kt.annotation_use_site")

    /** The `@file:…` block at the top of a file. */
    val FILE_ANNOTATION_LIST = NodeKind("kt.file_annotation_list")

    /** The `{ … }` of a class/object/interface, holding its members. */
    val CLASS_BODY = NodeKind("kt.class_body")

    /** The `: A, B by c` list after a class header, and the `: Base(1)` initializer list of an enum entry. */
    val SUPERTYPE_LIST = NodeKind("kt.supertype_list")

    /** A plain supertype in a [SUPERTYPE_LIST]: the `Runnable` of `: Runnable`. */
    val SUPERTYPE_ENTRY = NodeKind("kt.supertype_entry")

    /** A supertype whose constructor is invoked: the `Base(1)` of `: Base(1)`. */
    val SUPERTYPE_CALL = NodeKind("kt.supertype_call")

    /** A delegated supertype: the `I by impl` of `: I by impl`. */
    val SUPERTYPE_DELEGATE = NodeKind("kt.supertype_delegate")

    /** A `get()`/`set(v)` on a [PROPERTY]. */
    val PROPERTY_ACCESSOR = NodeKind("kt.property_accessor")

    /** The `by lazy { … }` of a delegated property. */
    val PROPERTY_DELEGATE = NodeKind("kt.property_delegate")

    /** An `init { … }` block. */
    val INIT = NodeKind("kt.init")

    /** The import block as a whole; each [IMPORT_DECL] is a child. */
    val IMPORT_LIST = NodeKind("kt.import_list")

    /** The `as Alias` of an aliased import. */
    val IMPORT_ALIAS = NodeKind("kt.import_alias")

    // --- KDoc ---
    /** A `[Foo.bar]` link inside a KDoc comment. */
    val KDOC_LINK = NodeKind("kt.kdoc_link")

    /** The qualified name inside a [KDOC_LINK], or the subject of a `@param`/`@property` tag. */
    val KDOC_NAME = NodeKind("kt.kdoc_name")

    /** Any adapted KtElement without a more specific kind; keeps the tree total. */
    val OTHER = NodeKind("kt.element")
}
