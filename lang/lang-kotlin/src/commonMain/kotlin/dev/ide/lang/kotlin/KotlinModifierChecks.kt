package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCatchClause
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassBody
import dev.ide.kotlin.syntax.psi.KtClassInitializer
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtDestructuringDeclaration
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtEnumEntry
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtForExpression
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtParameterList
import dev.ide.kotlin.syntax.psi.KtPrimaryConstructor
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtPropertyAccessor
import dev.ide.kotlin.syntax.psi.KtSecondaryConstructor
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeAlias
import dev.ide.kotlin.syntax.psi.KtTypeParameter
import dev.ide.kotlin.syntax.psi.KtModifierKeywordToken
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange

/**
 * Where each modifier keyword may be written, read off the tree alone: `abstract` on a top-level function,
 * `inner` on an object, `protected` inside an object, `final` inside an interface. A port of the compiler's
 * modifier checker (WRONG_MODIFIER_TARGET / WRONG_MODIFIER_CONTAINING_DECLARATION) and of the target tables
 * it reads, so a modifier is judged against exactly the kinds of declaration the compiler allows for it.
 *
 * The tables are the compiler's own. What is left out is what depends on a language feature or on facts the
 * tree does not settle: whether a member property has a backing field (for `inline`), companion extensions,
 * the deprecated `inline`/`external` placements, `value object`, and anything in a script. Those back off.
 */
internal object KotlinModifierChecks {

    /** The compiler's `KotlinTarget`, cut down to the kinds a source declaration can be. */
    private enum class T(val description: String) {
        CLASS("class"), ANNOTATION_CLASS("annotation class"), TYPE_PARAMETER("type parameter"), PROPERTY("property"),
        FIELD("field"), LOCAL_VARIABLE("local variable"), VALUE_PARAMETER("value parameter"), CONSTRUCTOR("constructor"),
        FUNCTION("function"), PROPERTY_GETTER("getter"), PROPERTY_SETTER("setter"), TYPE("type usage"), EXPRESSION("expression"),
        FILE("file"), TYPEALIAS("typealias"), PROPERTY_PARAMETER("property constructor parameter"),
        CLASS_ONLY("class"), OBJECT("object"), STANDALONE_OBJECT("standalone object"), COMPANION_OBJECT("companion object"),
        INTERFACE("interface"), ENUM_CLASS("enum class"), ENUM_ENTRY("enum entry"), LOCAL_CLASS("local class"),
        LOCAL_FUNCTION("local function"), MEMBER_FUNCTION("member function"), TOP_LEVEL_FUNCTION("top level function"),
        MEMBER_PROPERTY("member property"),
        MEMBER_PROPERTY_WITH_BACKING_FIELD("member property with backing field"),
        MEMBER_PROPERTY_WITH_DELEGATE("member property with delegate"),
        MEMBER_PROPERTY_WITHOUT_FIELD_OR_DELEGATE("member property without backing field or delegate"),
        TOP_LEVEL_PROPERTY("top level property"),
        TOP_LEVEL_PROPERTY_WITH_BACKING_FIELD("top level property with backing field"),
        TOP_LEVEL_PROPERTY_WITH_DELEGATE("top level property with delegate"),
        TOP_LEVEL_PROPERTY_WITHOUT_FIELD_OR_DELEGATE("top level property without backing field or delegate"),
        INITIALIZER("initializer"), DESTRUCTURING_DECLARATION("destructuring declaration"),
        ANONYMOUS_FUNCTION("anonymous function"), OBJECT_LITERAL("object literal"),

        /** Not a compiler target: a member or top-level property whose backing field the tree cannot settle. */
        PROPERTY_FIELD_UNKNOWN("property"),
    }

    fun check(decl: KtDeclaration): List<Diagnostic> {
        val ml = decl.modifierList ?: return emptyList()
        // A script's top level is a class body in disguise; its targets are not the ones read here.
        if (decl.containingFile.name.endsWith(".kts")) return emptyList()
        val mods = ml.children.filter { it.elementType in KtTokens.MODIFIER_KEYWORDS }
        if (mods.isEmpty()) return emptyList()
        val targets = targetsOf(decl) ?: return emptyList()
        val parents = parentTargets(decl)
        val out = ArrayList<Diagnostic>()
        // The compiler reports at most one error per modifier: one already flagged as repeated or incompatible
        // (by [KotlinSemanticChecks.modifierConflicts] or the compiler's fuller table) is not judged again here.
        val flagged = HashSet<KtElement>()
        for ((i, second) in mods.withIndex()) {
            for (j in 0 until i) markIncompatible(mods[j], second, decl, flagged)
            if (second in flagged) continue
            val token = second.elementType
            val target = wrongTarget(token, targets, decl)
            if (target != null) {
                out += diag(second, "Modifier '${second.text}' is not applicable to '$target'", KotlinDiagnosticCodes.WRONG_MODIFIER_TARGET)
                continue
            }
            if (token == KtTokens.EXPECT_KEYWORD && isInsideRegularClass(decl)) {
                out += diag(second, "Modifier 'expect' is not applicable to 'nested class'", KotlinDiagnosticCodes.WRONG_MODIFIER_TARGET)
                continue
            }
            if (parents != null) wrongContainer(token, parents, decl)?.let {
                out += diag(second, "Modifier '${second.text}' is not applicable inside '$it'", KotlinDiagnosticCodes.WRONG_MODIFIER_CONTAINER)
            }
        }
        return out
    }

    private fun diag(at: KtElement, msg: String, code: String): Diagnostic {
        val r = at.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, msg, code)
    }

    /** The description of the declaration's first target when [token] fits none of them; null when it fits,
     *  or when the answer rests on something this check does not model. */
    private fun wrongTarget(token: KtModifierKeywordToken, targets: List<T>, decl: KtDeclaration): String? {
        val allowed = POSSIBLE_TARGETS[token] ?: return null
        if (targets.any { it in allowed }) return null
        when (token) {
            // `inline` and `external` on an enum entry or a property parameter are only deprecated until a
            // language version this check cannot see, and `inline` on a member property turns on its field.
            KtTokens.INLINE_KEYWORD, KtTokens.EXTERNAL_KEYWORD ->
                if (T.ENUM_ENTRY in targets || T.PROPERTY_PARAMETER in targets || T.PROPERTY_FIELD_UNKNOWN in targets) return null
            // `value object` is legal under full value classes.
            KtTokens.VALUE_KEYWORD -> if (T.STANDALONE_OBJECT in targets) return null
            // `companion` on an extension is a companion extension, a feature of its own.
            KtTokens.COMPANION_KEYWORD ->
                if ((decl as? dev.ide.kotlin.syntax.psi.KtCallableDeclaration)?.receiverTypeReference != null) return null
        }
        return targets.first().description
    }

    /** The description of the containing declaration when [token] may not be written inside it, else null. */
    private fun wrongContainer(token: KtModifierKeywordToken, parents: List<T>, decl: KtDeclaration): String? {
        val where = parents.first().description
        // A companion object is never top-level (FILE is in the table only for companion extensions).
        if (token == KtTokens.COMPANION_KEYWORD) {
            if (decl !is KtClassOrObject) return null
            if (T.FILE in parents) return where
        }
        // The table lets `internal` into an interface only for companion-block members, which source here
        // never declares.
        if (token == KtTokens.INTERNAL_KEYWORD && T.INTERFACE in parents) return where
        val allowed = POSSIBLE_PARENTS[token] ?: return null
        return if (parents.any { it in allowed }) null else where
    }

    /** Marks the modifiers the compiler would already have reported as repeated or incompatible. */
    private fun markIncompatible(first: KtElement, second: KtElement, decl: KtDeclaration, flagged: MutableSet<KtElement>) {
        val a = first.elementType
        val b = second.elementType
        if (a == b) { flagged += second; return }
        val pair = setOf(a, b)
        val incompatible = INCOMPATIBLE.any { group -> a in group && b in group } &&
            pair != setOf(KtTokens.ABSTRACT_KEYWORD, KtTokens.OPEN_KEYWORD) &&
            pair != setOf(KtTokens.SEALED_KEYWORD, KtTokens.ABSTRACT_KEYWORD)
        val forClassesOnly = pair == setOf(KtTokens.PRIVATE_KEYWORD, KtTokens.OPEN_KEYWORD) ||
            pair == setOf(KtTokens.PRIVATE_KEYWORD, KtTokens.ABSTRACT_KEYWORD)
        if (incompatible || (forClassesOnly && decl !is KtClassOrObject)) { flagged += first; flagged += second }
    }

    // --- annotation targets (the compiler's FirAnnotationChecker / FirTypeAnnotationChecker) --------------------

    /**
     * An annotation written where its class's `@Target` does not allow it (WRONG_ANNOTATION_TARGET):
     * a `@Target(AnnotationTarget.FUNCTION)` annotation on a class, any annotation without a `@Target` on a type
     * or a type parameter. Judged only for an annotation class declared at the top level of this file under a
     * name nothing else in the file (an import, another class, an alias) also answers to, whose `@Target` lists
     * `AnnotationTarget` entries plainly. A use-site target (`@field:`), an annotated expression, and a property
     * whose backing field the tree cannot settle (for a FIELD-only annotation) are left alone.
     */
    fun annotationTarget(entry: dev.ide.kotlin.syntax.psi.KtAnnotationEntry): Diagnostic? {
        if (entry.useSiteTarget != null) return useSiteTarget(entry) ?: useSiteNotAllowedByClass(entry)
        val file = entry.containingFile
        if (file.name.endsWith(".kts")) return null
        val allowed = allowedTargetsOf(entry, file) ?: return null
        (entry.parent as? dev.ide.kotlin.syntax.psi.KtAnnotatedExpression)?.let { return expressionAnnotation(entry, it, allowed) }
        val owner = (entry.parent as? dev.ide.kotlin.syntax.psi.KtModifierList)?.parent ?: return null
        val (targets, substitutable) = when (owner) {
            is dev.ide.kotlin.syntax.psi.KtTypeReference -> listOf(T.TYPE) to emptyList()
            is KtDestructuringDeclaration -> return null
            is KtDeclaration -> {
                val t = targetsOf(owner) ?: return null
                val field = when {
                    owner is KtParameter && owner.hasValOrVar() -> listOf(T.FIELD)
                    owner is KtProperty && (T.MEMBER_PROPERTY_WITH_BACKING_FIELD in t || T.TOP_LEVEL_PROPERTY_WITH_BACKING_FIELD in t) -> listOf(T.FIELD)
                    else -> emptyList()
                }
                if (T.PROPERTY_FIELD_UNKNOWN in t && T.FIELD in allowed) return null
                t to field
            }
            else -> return null
        }
        if (targets.any { it in allowed } || substitutable.any { it in allowed }) return null
        val r = entry.textRange
        val applicable = ANNOTATION_TARGET_NAMES.filter { (_, t) -> t in allowed }.joinToString(", ") { it.second.description }
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "This annotation is not applicable to target '${targets.first().description}'. Applicable targets: $applicable",
            KotlinDiagnosticCodes.WRONG_ANNOTATION_TARGET,
        )
    }

    /** An annotation on an expression needs EXPRESSION among its targets. A lambda, an object literal or an
     *  anonymous function carries its annotations as a declaration, and a block is exempt, so those are left alone. */
    private fun expressionAnnotation(
        entry: dev.ide.kotlin.syntax.psi.KtAnnotationEntry, annotated: dev.ide.kotlin.syntax.psi.KtAnnotatedExpression, allowed: Set<T>,
    ): Diagnostic? {
        var base = annotated.baseExpression
        while (base is dev.ide.kotlin.syntax.psi.KtAnnotatedExpression) base = base.baseExpression
        when (base) {
            null, is dev.ide.kotlin.syntax.psi.KtLambdaExpression, is dev.ide.kotlin.syntax.psi.KtObjectLiteralExpression,
            is KtBlockExpression, is KtDeclaration -> return null
            else -> {}
        }
        if (T.EXPRESSION in allowed) return null
        val r = entry.textRange
        val applicable = ANNOTATION_TARGET_NAMES.filter { (_, t) -> t in allowed }.joinToString(", ") { it.second.description }
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "This annotation is not applicable to target 'expression'. Applicable targets: $applicable",
            KotlinDiagnosticCodes.WRONG_ANNOTATION_TARGET,
        )
    }

    /**
     * A use-site target naming something the declaration does not have (WRONG_ANNOTATION_TARGET_WITH_USE_SITE_TARGET):
     * `@field:` on a class or a function, `@get:` on a local variable, `@property:` on a plain parameter. Whatever
     * the annotation class allows, no such target exists there, so the class need not be known. Only declarations
     * whose targets do not depend on a backing field or a delegate are judged.
     */
    private fun useSiteTarget(entry: dev.ide.kotlin.syntax.psi.KtAnnotationEntry): Diagnostic? {
        if (entry.containingFile.name.endsWith(".kts")) return null
        val site = entry.useSiteTarget?.filterNot { it.isWhitespace() }?.removePrefix("@")?.removeSuffix(":") ?: return null
        val owner = (entry.parent as? dev.ide.kotlin.syntax.psi.KtModifierList)?.parent ?: return null
        // On a TYPE no use-site target applies (`@file:` included), save `@receiver:` on an extension's own receiver
        // type (`fun @receiver:A String.f()`): `i: @param:A Int` and `((@setparam:A Int) -> Unit)` are wrong.
        if (owner is dev.ide.kotlin.syntax.psi.KtTypeReference) {
            val receiverOf = owner.parent as? dev.ide.kotlin.syntax.psi.KtCallableDeclaration
            if (site == "receiver" && receiverOf != null && receiverOf.receiverTypeReference === owner) return null
            return useSiteDiagnostic(entry, "type usage", site)
        }
        val mapped = when (site) {
            "field", "delegate" -> T.FIELD
            "property" -> T.PROPERTY
            "get" -> T.PROPERTY_GETTER
            "set" -> T.PROPERTY_SETTER
            "param", "setparam", "receiver" -> T.VALUE_PARAMETER
            else -> return null
        }
        // `@receiver:` names an extension receiver, which only a function or property declared with one has.
        if (site == "receiver") {
            val callable = owner as? dev.ide.kotlin.syntax.psi.KtCallableDeclaration
            if (callable != null && (callable is KtNamedFunction || callable is KtProperty) && callable.receiverTypeReference != null) return null
            if (owner !is KtDeclaration || owner is KtEnumEntry || (owner as? KtObjectDeclaration)?.isObjectLiteral() == true) return null
            val what = when (owner) {
                is KtClassOrObject -> "class"
                is KtPrimaryConstructor, is KtSecondaryConstructor -> "constructor"
                is KtNamedFunction -> "function"
                is KtProperty -> if (owner.parent is KtBlockExpression) "local variable" else "member property"
                is KtParameter -> "value parameter"
                else -> return null
            }
            return useSiteDiagnostic(entry, what, site)
        }
        val (targets, extra) = when (owner) {
            is KtEnumEntry -> return null
            is KtClassOrObject -> if ((owner as? KtObjectDeclaration)?.isObjectLiteral() == true) return null else (classTargets(owner) ?: return null) to emptyList()
            is KtNamedFunction -> {
                if (owner.nameIdentifier == null) return null
                (targetsOf(owner) ?: return null) to listOf(T.VALUE_PARAMETER)
            }
            is KtPrimaryConstructor, is KtSecondaryConstructor, is KtTypeAlias, is KtTypeParameter -> (targetsOf(owner) ?: return null) to emptyList()
            is KtProperty -> if (owner.parent is KtBlockExpression) listOf(T.LOCAL_VARIABLE) to listOf(T.PROPERTY_SETTER, T.VALUE_PARAMETER) else return null
            is KtParameter -> {
                if (owner.hasValOrVar()) return null
                (parameterTargets(owner) ?: return null) to emptyList()
            }
            else -> return null
        }
        if (mapped in targets || mapped in extra) return null
        return useSiteDiagnostic(entry, targets.first().description, site)
    }

    /**
     * A use-site target the declaration has, on an annotation class that does not allow what it names:
     * `@delegate:A` with `@Target(PROPERTY)`, `@param:A` with `@Target(FIELD)`, `@receiver:A` with `@Target(FILE)`.
     * Judged only for an annotation class whose `@Target` this file spells out ([allowedTargetsOf]).
     */
    private fun useSiteNotAllowedByClass(entry: dev.ide.kotlin.syntax.psi.KtAnnotationEntry): Diagnostic? {
        val site = entry.useSiteTarget?.filterNot { it.isWhitespace() }?.removePrefix("@")?.removeSuffix(":") ?: return null
        val needed = when (site) {
            "field", "delegate" -> T.FIELD
            "property" -> T.PROPERTY
            "get" -> T.PROPERTY_GETTER
            "set" -> T.PROPERTY_SETTER
            "param", "setparam", "receiver" -> T.VALUE_PARAMETER
            // A misplaced `@file:` is INAPPLICABLE_FILE_TARGET's, not a mismatch with the class.
            else -> return null
        }
        val file = entry.containingFile
        if (file.name.endsWith(".kts")) return null
        val allowed = allowedTargetsOf(entry, file) ?: return null
        if (needed in allowed) return null
        return useSiteDiagnostic(entry, needed.description, site)
    }

    private fun useSiteDiagnostic(entry: dev.ide.kotlin.syntax.psi.KtAnnotationEntry, what: String, site: String): Diagnostic {
        val r = entry.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "This annotation is not applicable to target '$what' and use site target '@$site'",
            KotlinDiagnosticCodes.WRONG_ANNOTATION_TARGET_WITH_USE_SITE_TARGET,
        )
    }

    /** The targets the annotation class [entry] names allows, or null when that class is not plainly one of this
     *  file's own or its `@Target` cannot be read. */
    private fun allowedTargetsOf(entry: dev.ide.kotlin.syntax.psi.KtAnnotationEntry, file: KtFile): Set<T>? {
        val name = entry.typeReference?.text?.takeIf { n -> n.isNotEmpty() && n.all { it.isLetterOrDigit() || it == '_' } } ?: return null
        if (file.importDirectives.any { (it.aliasName ?: it.importedFqName?.shortName()?.asString()) == name }) return null
        val sameName = file.declarations.filter { (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == name }
        val cls = sameName.singleOrNull() as? KtClass ?: return null
        if (!cls.isAnnotation() || cls.isInterface() || cls.isEnum()) return null
        // A nested or local class of the same name, nearer the annotation, would win.
        var p = entry.parent
        while (p != null && p !is KtFile) {
            if (p is KtClassOrObject && p.declarations.any { (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == name }) return null
            if (p is KtBlockExpression && p.statements.any { (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == name }) return null
            p = p.parent
        }
        val targetEntries = cls.annotationEntries.filter { it.shortName?.asString() == "Target" }
        if (targetEntries.isEmpty()) return DEFAULT_ANNOTATION_TARGETS
        val target = targetEntries.singleOrNull() ?: return null
        val written = target.typeReference?.text
        if (written != "Target" && written != "kotlin.annotation.Target") return null
        if (written == "Target" && (file.importDirectives.any { (it.aliasName ?: it.importedFqName?.shortName()?.asString()) == "Target" } ||
                file.declarations.any { (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == "Target" })) return null
        val bareEntriesImported = file.importDirectives.any { d ->
            val fq = d.importedFqName?.asString() ?: return@any false
            d.isAllUnder && (fq == "kotlin.annotation.AnnotationTarget" || fq == "AnnotationTarget")
        }
        val out = HashSet<T>()
        for (arg in target.valueArguments) {
            if (arg.isSpread) return null
            val e = arg.getArgumentExpression() ?: return null
            val text = e.text.filterNot { it.isWhitespace() }
            val entryName = when {
                text.startsWith("AnnotationTarget.") -> text.removePrefix("AnnotationTarget.")
                text.startsWith("kotlin.annotation.AnnotationTarget.") -> text.removePrefix("kotlin.annotation.AnnotationTarget.")
                bareEntriesImported && e is dev.ide.kotlin.syntax.psi.KtNameReferenceExpression -> text
                else -> return null
            }
            // An entry naming no `AnnotationTarget` constant (`AnnotationTarget.INIT`, itself an unresolved reference)
            // adds nothing, as in K2: the list above is all fifteen, so what it lacks names nothing. Every other
            // entry still counts, and an annotation whose entries all fail has no target at all.
            ANNOTATION_TARGET_NAMES.firstOrNull { it.first == entryName }?.second?.let { out += it }
        }
        return out
    }

    /** `AnnotationTarget` entries, in declaration order, with the target each names. */
    private val ANNOTATION_TARGET_NAMES: List<Pair<String, T>> = listOf(
        "CLASS" to T.CLASS, "ANNOTATION_CLASS" to T.ANNOTATION_CLASS, "TYPE_PARAMETER" to T.TYPE_PARAMETER,
        "PROPERTY" to T.PROPERTY, "FIELD" to T.FIELD, "LOCAL_VARIABLE" to T.LOCAL_VARIABLE,
        "VALUE_PARAMETER" to T.VALUE_PARAMETER, "CONSTRUCTOR" to T.CONSTRUCTOR, "FUNCTION" to T.FUNCTION,
        "PROPERTY_GETTER" to T.PROPERTY_GETTER, "PROPERTY_SETTER" to T.PROPERTY_SETTER, "TYPE" to T.TYPE,
        "EXPRESSION" to T.EXPRESSION, "FILE" to T.FILE, "TYPEALIAS" to T.TYPEALIAS,
    )

    /** What an annotation class without `@Target` may annotate (the compiler's DEFAULT_TARGET_SET). */
    private val DEFAULT_ANNOTATION_TARGETS = setOf(
        T.CLASS, T.ANNOTATION_CLASS, T.PROPERTY, T.FIELD, T.LOCAL_VARIABLE, T.VALUE_PARAMETER, T.CONSTRUCTOR,
        T.FUNCTION, T.PROPERTY_GETTER, T.PROPERTY_SETTER,
    )

    // --- declaration shapes the compiler rejects outright --------------------------------------------------------

    /** More than one `vararg` parameter (MULTIPLE_VARARG_PARAMETERS), each one flagged. */
    fun multipleVarargs(params: dev.ide.kotlin.syntax.psi.KtParameterList): List<Diagnostic> {
        val owner = params.parent
        if (owner !is KtNamedFunction && owner !is KtPrimaryConstructor && owner !is KtSecondaryConstructor) return emptyList()
        val varargs = params.parameters.filter { it.isVarArg }
        if (varargs.size < 2 || suppressed(params, "MULTIPLE_VARARG_PARAMETERS")) return emptyList()
        return varargs.map { diag(it, "Multiple vararg-parameters are prohibited", KotlinDiagnosticCodes.DECLARATION_SHAPE) }
    }

    /**
     * Class declarations the compiler does not allow where they stand: type parameters on an object
     * (TYPE_PARAMETERS_IN_OBJECT), a named object or an interface declared in a function body
     * (LOCAL_OBJECT_NOT_ALLOWED / LOCAL_INTERFACE_NOT_ALLOWED), and a class nested, without `inner`, in an inner
     * class, a local class, an object literal or an enum entry (NESTED_CLASS_NOT_ALLOWED).
     */
    fun classPlacement(cls: KtClassOrObject): Diagnostic? {
        if (cls is KtEnumEntry || cls.containingFile.name.endsWith(".kts")) return null
        val obj = cls as? KtObjectDeclaration
        if (obj?.isObjectLiteral() == true) return null
        val at = cls.nameIdentifier ?: obj?.objectKeyword ?: cls
        if (obj != null && cls.typeParameterList != null) {
            return diag(cls.typeParameterList!!, "Type parameters are not allowed for objects", KotlinDiagnosticCodes.DECLARATION_SHAPE)
        }
        val isInterface = cls is KtClass && cls.isInterface()
        if (cls.parent is KtBlockExpression) {
            if (obj != null && !obj.isCompanion()) {
                return diag(at, "Named object '${cls.name}' is a singleton and cannot be local. Try to use anonymous object instead", KotlinDiagnosticCodes.DECLARATION_SHAPE)
            }
            if (isInterface) {
                return diag(at, "'${cls.name}' is an interface so it cannot be local. Try to use anonymous object or abstract class instead", KotlinDiagnosticCodes.DECLARATION_SHAPE)
            }
            return null
        }
        val container = (cls.parent as? KtClassBody)?.parent as? KtClassOrObject ?: return null
        val inner = cls is KtClass && cls.isInner()
        if (inner) return null
        val companion = obj?.isCompanion() == true
        val desc = when {
            companion -> "Companion object"
            obj != null -> "Object"
            isInterface -> "Interface"
            cls is KtClass && cls.isEnum() -> "Enum class"
            cls is KtClass && cls.isAnnotation() -> "Annotation class"
            else -> "Class"
        }
        if (container is KtEnumEntry) return if (companion) null else diag(at, "$desc is not allowed here", KotlinDiagnosticCodes.DECLARATION_SHAPE)
        val containerLocal = isLocalClass(container) ?: return null
        val containerInner = container is KtClass && container.isInner()
        if (companion) {
            // A companion of a local inner class has a code of its own.
            return if (containerInner && !containerLocal) diag(at, "$desc is not allowed here", KotlinDiagnosticCodes.DECLARATION_SHAPE) else null
        }
        // Objects and enums nested in a local class are local themselves, and reported under other codes.
        if ((obj != null || (cls is KtClass && cls.isEnum())) && containerLocal) return null
        if (containerInner || containerLocal) return diag(at, "$desc is not allowed here", KotlinDiagnosticCodes.DECLARATION_SHAPE)
        return null
    }

    /**
     * A `private set` on a member property that is abstract (PRIVATE_SETTER_FOR_ABSTRACT_PROPERTY) or that a
     * subclass could override (PRIVATE_SETTER_FOR_OPEN_PROPERTY): `open`, an `override` that is not `final`, or
     * an interface member, in a class that can itself be subclassed. Objects and enums are left alone.
     */
    fun privateSetter(prop: KtProperty): Diagnostic? {
        val setter = prop.setter ?: return null
        val mod = setter.modifierList?.getModifier(KtTokens.PRIVATE_KEYWORD) ?: return null
        if (prop.hasModifier(KtTokens.PRIVATE_KEYWORD)) return null
        val cls = (prop.parent as? KtClassBody)?.parent as? KtClass ?: return null
        if (cls is KtEnumEntry || cls.isEnum() || cls.isAnnotation() || cls.hasModifier(KtTokens.EXPECT_KEYWORD)) return null
        val iface = cls.isInterface()
        val bodiless = !prop.hasInitializer() && !prop.hasDelegate() && prop.accessors.none { it.bodyExpression != null }
        if (prop.hasModifier(KtTokens.ABSTRACT_KEYWORD) || (iface && bodiless)) {
            return diag(mod, "Private setters for abstract properties are prohibited", KotlinDiagnosticCodes.DECLARATION_SHAPE)
        }
        val open = !prop.hasModifier(KtTokens.FINAL_KEYWORD) &&
            (prop.hasModifier(KtTokens.OPEN_KEYWORD) || prop.hasModifier(KtTokens.OVERRIDE_KEYWORD) || iface)
        val classOpen = iface || cls.hasModifier(KtTokens.OPEN_KEYWORD) || cls.hasModifier(KtTokens.ABSTRACT_KEYWORD) || cls.isSealed()
        if (!open || !classOpen) return null
        return diag(mod, "Private setters for open properties are prohibited", KotlinDiagnosticCodes.DECLARATION_SHAPE)
    }

    /** A named function in expression position: `val f = fun named() {}` (ANONYMOUS_FUNCTION_WITH_NAME). */
    fun anonymousFunctionWithName(fn: KtNamedFunction): Diagnostic? {
        val id = fn.nameIdentifier ?: return null
        // A labeled or annotated local function is still a statement.
        var holder: KtElement = fn
        while (true) {
            val p = holder.parent ?: return null
            if (p is dev.ide.kotlin.syntax.psi.KtLabeledExpression || p is dev.ide.kotlin.syntax.psi.KtAnnotatedExpression) holder = p else break
        }
        when (holder.parent) {
            is KtFile, is KtClassBody, is KtBlockExpression -> return null
            else -> {}
        }
        if (fn.containingFile.name.endsWith(".kts")) return null
        return diag(id, "Anonymous functions with names are prohibited", KotlinDiagnosticCodes.DECLARATION_SHAPE)
    }

    /** Whether a `@Suppress("[name]")` on [at] or a declaration around it silences that compiler diagnostic. */
    private fun suppressed(at: KtElement, name: String): Boolean {
        var n: KtElement? = at
        while (n != null) {
            val entries = (n as? dev.ide.kotlin.syntax.psi.KtAnnotated)?.annotationEntries.orEmpty()
            if (entries.any { it.shortName?.asString() == "Suppress" && it.valueArguments.any { a -> a.getArgumentExpression()?.text == "\"$name\"" } }) return true
            n = n.parent
        }
        return false
    }

    /** `in`/`out` on a type parameter of a function, property or type alias (VARIANCE_ON_TYPE_PARAMETER_NOT_ALLOWED). */
    fun varianceOnTypeParameter(tp: KtTypeParameter): Diagnostic? {
        if (!tp.hasModifier(KtTokens.IN_KEYWORD) && !tp.hasModifier(KtTokens.OUT_KEYWORD)) return null
        val owner = tp.parent?.parent
        if (owner !is KtNamedFunction && owner !is KtProperty && owner !is KtTypeAlias) return null
        return diag(tp, "Variance annotations are only allowed for type parameters of classes and interfaces", KotlinDiagnosticCodes.DECLARATION_SHAPE)
    }

    /** A nullable parameter type in an annotation class's constructor (NULLABLE_TYPE_OF_ANNOTATION_MEMBER). */
    fun nullableAnnotationMember(cls: KtClass): List<Diagnostic> {
        if (!cls.isAnnotation()) return emptyList()
        return cls.primaryConstructorParameters.mapNotNull { p ->
            val type = p.typeReference ?: return@mapNotNull null
            if (type.typeElement !is dev.ide.kotlin.syntax.psi.KtNullableType) return@mapNotNull null
            diag(type, "An annotation parameter cannot be nullable", KotlinDiagnosticCodes.DECLARATION_SHAPE)
        }
    }

    /** `a@ b@ expr`: an expression may carry one label (MULTIPLE_LABELS_ARE_FORBIDDEN), flagged on each outer one. */
    fun multipleLabels(e: dev.ide.kotlin.syntax.psi.KtLabeledExpression): Diagnostic? {
        if (e.baseExpression !is dev.ide.kotlin.syntax.psi.KtLabeledExpression) return null
        // Only a statement is judged; a doubly labeled operand (`(a@ b@ s)()`) is something else.
        if (e.parent !is KtBlockExpression) return null
        val label = e.labelQualifier ?: return null
        return diag(label, "Multiple labels per statement are forbidden", KotlinDiagnosticCodes.DECLARATION_SHAPE)
    }

    /**
     * The same annotation written twice on one element (REPEATED_ANNOTATION) when its class is this file's own
     * and is not `@Repeatable`. Entries with the same text are already reported as repeated modifiers.
     */
    fun repeatedAnnotations(list: dev.ide.kotlin.syntax.psi.KtModifierList): List<Diagnostic> {
        // A property's annotations, its accessors' and its setter parameter's are judged together by
        // [propertyRepeatedAnnotations], by the element each one lands on.
        if (propertyAnnotationOwner(list) != null) return emptyList()
        val entries = list.annotationEntries
        if (entries.size < 2) return emptyList()
        val file = list.containingFile
        val out = ArrayList<Diagnostic>()
        val seen = HashMap<String, MutableList<dev.ide.kotlin.syntax.psi.KtAnnotationEntry>>()
        for (e in entries) {
            val name = e.typeReference?.text ?: continue
            val key = (e.useSiteTarget ?: "") + "|" + name
            val earlier = seen.getOrPut(key) { ArrayList() }
            // One not allowed on this element at all is WRONG_ANNOTATION_TARGET each time, never a repeat.
            val misplaced = list.parent is dev.ide.kotlin.syntax.psi.KtTypeParameter &&
                allowedTargetsOf(e, file)?.let { T.TYPE_PARAMETER !in it } == true
            if (!misplaced && earlier.isNotEmpty() && earlier.none { it.text.trim() == e.text.trim() } && isNonRepeatableOwnAnnotation(e, file)) {
                out += diag(e, "This annotation is not repeatable", KotlinDiagnosticCodes.DECLARATION_SHAPE)
            }
            earlier += e
        }
        return out
    }

    /** The property (or primary-constructor property) whose annotation sites [list] is one of, or null. */
    private fun propertyAnnotationOwner(list: dev.ide.kotlin.syntax.psi.KtModifierList): KtElement? {
        return when (val owner = list.parent) {
            is dev.ide.kotlin.syntax.psi.KtProperty -> owner.takeIf { !it.isLocal }
            is dev.ide.kotlin.syntax.psi.KtPropertyAccessor -> owner.parent as? dev.ide.kotlin.syntax.psi.KtProperty
            is dev.ide.kotlin.syntax.psi.KtParameter -> when {
                owner.hasValOrVar() && owner.parent?.parent is dev.ide.kotlin.syntax.psi.KtPrimaryConstructor -> owner
                (owner.parent?.parent as? dev.ide.kotlin.syntax.psi.KtPropertyAccessor)?.isSetter == true ->
                    owner.parent?.parent?.parent as? dev.ide.kotlin.syntax.psi.KtProperty
                else -> null
            }
            else -> null
        }
    }

    private enum class Site { PARAM, PROPERTY, FIELD, GETTER, SETTER, SETTER_PARAM, DELEGATE, RECEIVER }

    /**
     * REPEATED_ANNOTATION across the elements a property's annotations land on, as K2 places them: `@get:A` on
     * the property and `@A` on its getter are one getter's two annotations, and an unprefixed `@A` goes to the
     * constructor parameter of a primary-constructor property, else the property, else the field, by what `A`'s
     * targets allow. For an accessor the property's prefixed annotations come first; for the setter parameter its
     * own do (the later ones are reported). Judged for this file's own non-repeatable annotation classes.
     */
    fun propertyRepeatedAnnotations(owner: KtElement): List<Diagnostic> {
        val file = owner.containingFile as? KtFile ?: return emptyList()
        val ctorParam = owner as? dev.ide.kotlin.syntax.psi.KtParameter
        val prop = owner as? dev.ide.kotlin.syntax.psi.KtProperty
        val ownEntries = (ctorParam?.modifierList ?: prop?.modifierList)?.annotationEntries.orEmpty()
        val getter = prop?.getter
        val setter = prop?.setter
        val setterParam = setter?.valueParameters?.firstOrNull()
        val bySite = LinkedHashMap<Site, MutableList<dev.ide.kotlin.syntax.psi.KtAnnotationEntry>>()
        fun add(site: Site, e: dev.ide.kotlin.syntax.psi.KtAnnotationEntry) { bySite.getOrPut(site) { ArrayList() } += e }
        fun siteOf(e: dev.ide.kotlin.syntax.psi.KtAnnotationEntry): Site? = when (e.useSiteTarget?.trim()) {
            "param" -> Site.PARAM; "property" -> Site.PROPERTY; "field" -> Site.FIELD; "get" -> Site.GETTER
            "set" -> Site.SETTER; "setparam" -> Site.SETTER_PARAM; "delegate" -> Site.DELEGATE; "receiver" -> Site.RECEIVER
            null, "" -> {
                val targets = allowedTargetsOf(e, file)
                when {
                    targets == null -> null
                    ctorParam != null && T.VALUE_PARAMETER in targets -> Site.PARAM
                    T.PROPERTY in targets -> Site.PROPERTY
                    T.FIELD in targets -> Site.FIELD
                    else -> null
                }
            }
            else -> null
        }
        val propertySites = ownEntries.map { it to siteOf(it) }
        // Setter parameter: its own annotations, then the property's `@setparam:` ones.
        setterParam?.modifierList?.annotationEntries.orEmpty().forEach { add(Site.SETTER_PARAM, it) }
        for ((e, site) in propertySites) if (site != null && site != Site.GETTER && site != Site.SETTER) add(site, e)
        // Accessors: the property's `@get:`/`@set:` ones, then the accessor's own.
        for ((e, site) in propertySites) if (site == Site.GETTER || site == Site.SETTER) add(site, e)
        getter?.modifierList?.annotationEntries.orEmpty().forEach { add(Site.GETTER, it) }
        setter?.modifierList?.annotationEntries.orEmpty().forEach { add(Site.SETTER, it) }
        val out = ArrayList<Diagnostic>()
        for ((_, entries) in bySite) {
            val seen = HashMap<String, MutableList<dev.ide.kotlin.syntax.psi.KtAnnotationEntry>>()
            for (e in entries) {
                val name = e.typeReference?.text ?: continue
                val earlier = seen.getOrPut(name) { ArrayList() }
                // The very same text twice in one list is reported as a repeated modifier instead.
                val sameTextSameList = earlier.any { it.parent === e.parent && it.text.trim() == e.text.trim() }
                if (earlier.isNotEmpty() && !sameTextSameList && isNonRepeatableOwnAnnotation(e, file)) {
                    out += diag(e, "This annotation is not repeatable", KotlinDiagnosticCodes.DECLARATION_SHAPE)
                }
                earlier += e
            }
        }
        return out
    }

    private fun isNonRepeatableOwnAnnotation(e: dev.ide.kotlin.syntax.psi.KtAnnotationEntry, file: KtFile): Boolean {
        if (allowedTargetsOf(e, file) == null) return false
        val cls = file.declarations.first { (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == e.typeReference?.text } as KtClass
        return cls.annotationEntries.none { val n = it.shortName?.asString(); n == "Repeatable" || n == "JvmRepeatable" }
    }

    // --- operator and infix functions (the compiler's OperatorFunctionChecks / infix checker) --------------------

    /**
     * An `operator` function whose name or shape no operator convention accepts: `operator fun and`, a `plus`
     * with no parameter, a `contains` returning `String`, a top-level non-extension `get`. Anchored on the
     * `operator` keyword, worded as the compiler words it. A return type is judged only when it is written as
     * a plain built-in the file does not redeclare; `inc`/`dec` (a subtyping question) and `of` (a language
     * feature) are left alone.
     */
    fun operatorModifier(fn: KtNamedFunction): Diagnostic? {
        val mod = fn.modifierList?.getModifier(KtTokens.OPERATOR_KEYWORD) ?: return null
        if (fn.containingFile.name.endsWith(".kts")) return null
        fun d(why: String) = diag(mod, "'operator' modifier is not applicable to function: $why", KotlinDiagnosticCodes.INAPPLICABLE_OPERATOR)
        val name = fn.name ?: return if (fn.parent !is KtClassBody && fn.parent !is KtFile) d("anonymous function") else null
        val params = fn.valueParameters
        val member = fn.parent is KtClassBody
        val memberOrExtension = member || fn.receiverTypeReference != null
        fun noDefaultsOrVarargs(): String? = when {
            params.any { it.hasDefaultValue() } -> "must not have parameters with default values"
            params.any { it.isVarArg } -> "must not have varargs"
            else -> null
        }
        fun returns(type: String): String? = if (declaredReturnTypeIs(fn, type) == false) "must return '$type'" else null
        val notMemberOrExt = if (memberOrExtension) null else "must be a member or an extension function"
        val single = if (params.size == 1) null else "must have a single value parameter"
        val none = if (params.isEmpty()) null else "must have no value parameters"
        fun atLeast(n: Int) = if (params.size >= n) null else "must have at least $n value parameter" + if (n > 1) "s" else ""
        val problem: String? = when (name) {
            "get" -> notMemberOrExt ?: atLeast(1)
            "set" -> notMemberOrExt ?: atLeast(2)
                ?: params.last().let { if (it.hasDefaultValue() || it.isVarArg) "last parameter must not have a default value or be a vararg" else null }
            "getValue", "setValue", "provideDelegate" -> notMemberOrExt ?: noDefaultsOrVarargs()
                ?: when (name) {
                    "getValue" -> atLeast(2)
                    "setValue" -> atLeast(3)
                    else -> if (params.size == 2) null else "must have exactly 2 value parameters"
                }
                ?: params.getOrNull(1)?.let { p ->
                    // Nothing built in but Any is a supertype of KProperty<*>.
                    val t = builtinTypeName(p.typeReference?.text, fn)
                    if (t != null && t != "Any") "second parameter must be of type KProperty<*> or its supertype" else null
                }
                ?: if (fn.hasModifier(KtTokens.SUSPEND_KEYWORD)) "must not be suspend" else null
            "invoke" -> notMemberOrExt
            "inc", "dec" -> notMemberOrExt ?: incDecReturn(fn)
            "contains" -> notMemberOrExt ?: single ?: noDefaultsOrVarargs() ?: returns("Boolean")
            "iterator", "next" -> notMemberOrExt ?: none
            "hasNext" -> notMemberOrExt ?: none ?: returns("Boolean")
            "equals" -> when {
                !member -> "must be a member function"
                params.size != 1 -> "must override 'equals()' in Any"
                // A parameter of a built-in type other than Any cannot be the one `Any.equals` declares.
                builtinTypeName(params[0].typeReference?.text, fn).let { it != null && it != "Any" } &&
                    (fn.parent?.parent as? KtClass)?.let { it.isValue() || it.hasModifier(KtTokens.INLINE_KEYWORD) } != true ->
                    "must override 'equals()' in Any"
                else -> null
            }
            "compareTo" -> notMemberOrExt ?: returns("Int") ?: single ?: noDefaultsOrVarargs()
            "times", "plus", "minus", "div", "rem", "rangeTo", "rangeUntil" -> notMemberOrExt ?: single ?: noDefaultsOrVarargs()
            "unaryPlus", "unaryMinus", "not", "inv" -> notMemberOrExt ?: none
            "timesAssign", "divAssign", "remAssign", "plusAssign", "minusAssign" ->
                notMemberOrExt ?: returns("Unit") ?: single ?: noDefaultsOrVarargs()
            "of" -> null
            else -> if (COMPONENT_N.matches(name)) notMemberOrExt ?: none else "illegal function name"
        }
        return problem?.let(::d)
    }

    /** An `infix` function that is not a member or extension taking exactly one non-vararg parameter. */
    fun infixModifier(fn: KtNamedFunction): Diagnostic? {
        val mod = fn.modifierList?.getModifier(KtTokens.INFIX_KEYWORD) ?: return null
        if (fn.containingFile.name.endsWith(".kts")) return null
        val params = fn.valueParameters
        val ok = params.size == 1 && !params[0].isVarArg && (fn.parent is KtClassBody || fn.receiverTypeReference != null)
        return if (ok) null else diag(mod, "'infix' modifier is inapplicable to this function", KotlinDiagnosticCodes.INAPPLICABLE_INFIX)
    }

    /** Whether [fn]'s return type is the built-in [type]: false only when it is plainly something else (another
     *  built-in written out, or `Unit` from a block body), null when the tree cannot say. */
    private fun declaredReturnTypeIs(fn: KtNamedFunction, type: String): Boolean? {
        val written = writtenReturnType(fn) ?: return null
        if (written == type) return true
        if (builtinTypeName(written, fn) == null) return null
        return false
    }

    /** The return type as written, `Unit` for a block-bodied or bodiless function without one, else null. */
    private fun writtenReturnType(fn: KtNamedFunction): String? {
        val ref = fn.typeReference
        return if (ref == null) {
            if (fn.hasBlockBody() || fn.bodyExpression == null) "Unit" else null
        } else ref.text.filterNot { it.isWhitespace() }.removePrefix("kotlin.")
    }

    /** The built-in type [text] names (nullable or not), unless the file declares something of that name. */
    private fun builtinTypeName(text: String?, at: KtElement): String? {
        val base = text?.filterNot { it.isWhitespace() }?.removePrefix("kotlin.")?.removeSuffix("?") ?: return null
        if (base !in BUILTIN_TYPE_NAMES) return null
        // A file that declares a class or alias of its own under a built-in's name is not judged.
        if (Regex("""\b(class|interface|object|typealias)\s+${base}\b""").containsMatchIn(at.containingFile.sourceText)) return null
        return base
    }

    /** For a member `inc`/`dec`: its return type must be a subtype of the class it is declared in, which a
     *  built-in (other than Nothing) or a nullable form of the class never is. */
    private fun incDecReturn(fn: KtNamedFunction): String? {
        val cls = (fn.parent as? KtClassBody)?.parent as? KtClassOrObject ?: return null
        val name = cls.name ?: return null
        if (cls is KtObjectDeclaration && cls.isObjectLiteral()) return null
        val written = writtenReturnType(fn) ?: return null
        val wrong = written == "$name?" || builtinTypeName(written, fn).let { it != null && it != "Nothing" && it != name }
        return if (wrong) "receiver must be a supertype of the return type" else null
    }

    private val COMPONENT_N = Regex("""component\d+""")
    private val BUILTIN_TYPE_NAMES = setOf(
        "Int", "Long", "Short", "Byte", "Double", "Float", "Char", "Boolean", "String", "Unit", "Any", "Nothing",
    )

    // --- what a declaration is ---------------------------------------------------------------------------------

    /** Whether [decl] carries its own keyword. Without one, the parser has read something else (a call named
     *  `inline` on the line after a class, an enum entry named `value`) as a modifier of what follows. */
    private fun wellFormed(decl: KtDeclaration): Boolean = when (decl) {
        is KtNamedFunction -> decl.funKeyword != null
        is KtPrimaryConstructor -> decl.children.any { it.elementType == KtTokens.CONSTRUCTOR_KEYWORD }
        is KtProperty -> decl.children.any { it.elementType == KtTokens.VAL_KEYWORD || it.elementType == KtTokens.VAR_KEYWORD }
        else -> true
    }

    private fun targetsOf(decl: KtDeclaration): List<T>? = if (!wellFormed(decl)) null else when (decl) {
        is KtEnumEntry -> listOf(T.ENUM_ENTRY, T.PROPERTY, T.FIELD)
        is KtClassOrObject -> classTargets(decl)
        is KtProperty -> propertyTargets(decl)
        is KtParameter -> parameterTargets(decl)
        is KtNamedFunction -> when (decl.parent) {
            is KtClassBody -> listOf(T.MEMBER_FUNCTION, T.FUNCTION)
            is KtFile -> listOf(T.TOP_LEVEL_FUNCTION, T.FUNCTION)
            else -> when {
                decl.nameIdentifier == null -> listOf(T.ANONYMOUS_FUNCTION, T.FUNCTION, T.EXPRESSION)
                decl.parent is KtBlockExpression -> listOf(T.LOCAL_FUNCTION, T.FUNCTION)
                else -> null
            }
        }
        is KtPrimaryConstructor, is KtSecondaryConstructor -> listOf(T.CONSTRUCTOR)
        is KtTypeAlias -> listOf(T.TYPEALIAS)
        is KtPropertyAccessor -> listOf(if (decl.isGetter) T.PROPERTY_GETTER else T.PROPERTY_SETTER)
        is KtTypeParameter -> listOf(T.TYPE_PARAMETER)
        is KtClassInitializer -> listOf(T.INITIALIZER)
        is KtDestructuringDeclaration -> if (decl.parent is KtBlockExpression) listOf(T.DESTRUCTURING_DECLARATION) else null
        // Each entry of a local destructuring declaration is a local variable of its own.
        is dev.ide.kotlin.syntax.psi.KtDestructuringDeclarationEntry ->
            if (decl.parent?.parent is KtBlockExpression) listOf(T.LOCAL_VARIABLE) else null
        else -> null
    }

    private fun classTargets(cls: KtClassOrObject): List<T>? = when (cls) {
        is KtObjectDeclaration -> when {
            cls.isObjectLiteral() -> listOf(T.OBJECT_LITERAL, T.CLASS, T.EXPRESSION)
            cls.isCompanion() -> listOf(T.COMPANION_OBJECT, T.OBJECT, T.CLASS)
            else -> listOf(T.STANDALONE_OBJECT, T.OBJECT, T.CLASS)
        }
        is KtClass -> {
            val local = isLocalClass(cls) ?: return null
            // The compiler's order: `interface` first, then `enum`, then `annotation`.
            when {
                cls.isInterface() -> listOf(T.INTERFACE, T.CLASS)
                cls.isEnum() -> if (local) listOf(T.LOCAL_CLASS, T.CLASS) else listOf(T.ENUM_CLASS, T.CLASS)
                cls.isAnnotation() -> listOf(T.ANNOTATION_CLASS, T.CLASS)
                // An inner class of a local class is CLASS_ONLY, not LOCAL_CLASS.
                !cls.isInner() && local -> listOf(T.LOCAL_CLASS, T.CLASS)
                else -> listOf(T.CLASS_ONLY, T.CLASS)
            }
        }
        else -> null
    }

    /** Whether [cls] is local (inside a function, a lambda, an initializer or an object literal, or nested in a
     *  class that is); null inside an enum entry's body, whose members the compiler hangs on an anonymous object. */
    private fun isLocalClass(cls: KtClassOrObject): Boolean? {
        var n = cls.parent
        while (n != null) {
            when (n) {
                is KtFile -> return false
                is KtEnumEntry -> return null
                is KtObjectDeclaration -> if (n.isObjectLiteral()) return true
                is KtClassBody, is KtClassOrObject -> {}
                else -> return true
            }
            n = n.parent
        }
        return false
    }

    private fun propertyTargets(p: KtProperty): List<T>? {
        val owner = p.parent
        if (owner !is KtClassBody && owner !is KtFile) return if (owner is KtBlockExpression) listOf(T.LOCAL_VARIABLE) else null
        val member = owner is KtClassBody
        if (member && (owner.parent as? KtClassOrObject)?.let { it is KtEnumEntry } != false) return null
        val field = when {
            p.hasDelegate() -> if (member) T.MEMBER_PROPERTY_WITH_DELEGATE else T.TOP_LEVEL_PROPERTY_WITH_DELEGATE
            p.hasInitializer() -> if (member) T.MEMBER_PROPERTY_WITH_BACKING_FIELD else T.TOP_LEVEL_PROPERTY_WITH_BACKING_FIELD
            else -> T.PROPERTY_FIELD_UNKNOWN
        }
        return listOf(field, if (member) T.MEMBER_PROPERTY else T.TOP_LEVEL_PROPERTY, T.PROPERTY)
    }

    private fun parameterTargets(p: KtParameter): List<T>? {
        when (val owner = p.parent) {
            is KtForExpression -> return listOf(T.VALUE_PARAMETER)
            is KtParameterList -> when (owner.parent) {
                is KtCatchClause -> return listOf(T.LOCAL_VARIABLE, T.VALUE_PARAMETER)
                is KtNamedFunction, is KtPrimaryConstructor, is KtSecondaryConstructor, is KtFunctionLiteral, is KtPropertyAccessor -> {}
                // A function type's parameters and context parameters are checked by rules of their own.
                else -> return null
            }
            else -> return null
        }
        return if (p.hasValOrVar()) listOf(T.VALUE_PARAMETER, T.PROPERTY, T.MEMBER_PROPERTY, T.PROPERTY_PARAMETER)
        else listOf(T.VALUE_PARAMETER)
    }

    /** What the declaration sits in, as the compiler sees it: the nearest class, function, accessor or file,
     *  looking through a primary constructor and a property. Null where that is not a plain answer. */
    private fun parentTargets(decl: KtDeclaration): List<T>? {
        var n = decl.parent
        while (n != null) {
            when (n) {
                is KtEnumEntry -> return listOf(T.LOCAL_CLASS, T.CLASS) // an entry's body is an anonymous object
                is KtObjectDeclaration -> return if (n.isObjectLiteral()) listOf(T.LOCAL_CLASS, T.CLASS) else classTargets(n)
                is KtClass -> return classTargets(n)
                is KtPropertyAccessor -> return listOf(if (n.isGetter) T.PROPERTY_GETTER else T.PROPERTY_SETTER)
                is KtNamedFunction, is KtSecondaryConstructor, is KtFunctionLiteral -> return listOf(T.FUNCTION)
                is KtFile -> return listOf(T.FILE)
                is KtClassInitializer, is KtParameter, is KtTypeAlias -> return null
                else -> {}
            }
            n = n.parent
        }
        return null
    }

    private fun isInsideRegularClass(decl: KtDeclaration): Boolean {
        val body = decl.parent as? KtClassBody ?: return false
        val owner = body.parent as? KtClassOrObject ?: return false
        return owner !is KtEnumEntry && !(owner is KtObjectDeclaration && owner.isObjectLiteral())
    }

    // --- the compiler's tables (ModifierCheckerHelpers) ---------------------------------------------------------

    private val DEFAULT_VISIBILITY = setOf(
        T.CLASS_ONLY, T.OBJECT, T.INTERFACE, T.ENUM_CLASS, T.ANNOTATION_CLASS, T.MEMBER_FUNCTION, T.TOP_LEVEL_FUNCTION,
        T.PROPERTY_GETTER, T.PROPERTY_SETTER, T.MEMBER_PROPERTY, T.TOP_LEVEL_PROPERTY, T.CONSTRUCTOR, T.TYPEALIAS,
    )

    private val POSSIBLE_TARGETS: Map<KtModifierKeywordToken, Set<T>> = mapOf(
        KtTokens.ENUM_KEYWORD to setOf(T.ENUM_CLASS),
        KtTokens.ABSTRACT_KEYWORD to setOf(T.CLASS_ONLY, T.LOCAL_CLASS, T.INTERFACE, T.MEMBER_PROPERTY, T.MEMBER_FUNCTION),
        KtTokens.OPEN_KEYWORD to setOf(T.CLASS_ONLY, T.LOCAL_CLASS, T.INTERFACE, T.MEMBER_PROPERTY, T.MEMBER_FUNCTION),
        KtTokens.FINAL_KEYWORD to setOf(T.CLASS_ONLY, T.LOCAL_CLASS, T.ENUM_CLASS, T.OBJECT, T.MEMBER_PROPERTY, T.MEMBER_FUNCTION),
        KtTokens.SEALED_KEYWORD to setOf(T.CLASS_ONLY, T.INTERFACE),
        KtTokens.INNER_KEYWORD to setOf(T.CLASS_ONLY),
        KtTokens.OVERRIDE_KEYWORD to setOf(T.MEMBER_PROPERTY, T.MEMBER_FUNCTION),
        KtTokens.PRIVATE_KEYWORD to DEFAULT_VISIBILITY,
        KtTokens.PUBLIC_KEYWORD to DEFAULT_VISIBILITY,
        KtTokens.INTERNAL_KEYWORD to DEFAULT_VISIBILITY,
        KtTokens.PROTECTED_KEYWORD to setOf(
            T.CLASS_ONLY, T.OBJECT, T.INTERFACE, T.ENUM_CLASS, T.ANNOTATION_CLASS, T.MEMBER_FUNCTION, T.PROPERTY_GETTER,
            T.PROPERTY_SETTER, T.MEMBER_PROPERTY, T.CONSTRUCTOR, T.TYPEALIAS,
        ),
        KtTokens.IN_KEYWORD to setOf(T.TYPE_PARAMETER),
        KtTokens.OUT_KEYWORD to setOf(T.TYPE_PARAMETER),
        KtTokens.REIFIED_KEYWORD to setOf(T.TYPE_PARAMETER),
        KtTokens.VARARG_KEYWORD to setOf(T.VALUE_PARAMETER, T.PROPERTY_PARAMETER),
        KtTokens.COMPANION_KEYWORD to setOf(T.OBJECT),
        KtTokens.LATEINIT_KEYWORD to setOf(T.MEMBER_PROPERTY, T.TOP_LEVEL_PROPERTY, T.LOCAL_VARIABLE),
        KtTokens.DATA_KEYWORD to setOf(T.CLASS_ONLY, T.LOCAL_CLASS, T.STANDALONE_OBJECT),
        KtTokens.INLINE_KEYWORD to setOf(
            T.FUNCTION, T.MEMBER_PROPERTY_WITH_DELEGATE, T.MEMBER_PROPERTY_WITHOUT_FIELD_OR_DELEGATE, T.TOP_LEVEL_PROPERTY,
            T.PROPERTY_GETTER, T.PROPERTY_SETTER, T.CLASS_ONLY,
        ),
        KtTokens.NOINLINE_KEYWORD to setOf(T.VALUE_PARAMETER),
        KtTokens.TAILREC_KEYWORD to setOf(T.FUNCTION),
        KtTokens.SUSPEND_KEYWORD to setOf(T.MEMBER_FUNCTION, T.TOP_LEVEL_FUNCTION, T.LOCAL_FUNCTION, T.ANONYMOUS_FUNCTION),
        KtTokens.EXTERNAL_KEYWORD to setOf(
            T.FUNCTION, T.MEMBER_PROPERTY_WITH_BACKING_FIELD, T.MEMBER_PROPERTY_WITH_DELEGATE,
            T.MEMBER_PROPERTY_WITHOUT_FIELD_OR_DELEGATE, T.TOP_LEVEL_PROPERTY, T.PROPERTY_GETTER, T.PROPERTY_SETTER, T.CLASS,
        ),
        KtTokens.ANNOTATION_KEYWORD to setOf(T.ANNOTATION_CLASS),
        KtTokens.CROSSINLINE_KEYWORD to setOf(T.VALUE_PARAMETER),
        KtTokens.CONST_KEYWORD to setOf(T.MEMBER_PROPERTY, T.TOP_LEVEL_PROPERTY),
        KtTokens.OPERATOR_KEYWORD to setOf(T.FUNCTION),
        KtTokens.INFIX_KEYWORD to setOf(T.FUNCTION),
        KtTokens.EXPECT_KEYWORD to setOf(
            T.TOP_LEVEL_FUNCTION, T.TOP_LEVEL_PROPERTY, T.CLASS_ONLY, T.OBJECT, T.INTERFACE, T.ENUM_CLASS, T.ANNOTATION_CLASS,
        ),
        KtTokens.ACTUAL_KEYWORD to setOf(
            T.TOP_LEVEL_FUNCTION, T.MEMBER_FUNCTION, T.TOP_LEVEL_PROPERTY, T.MEMBER_PROPERTY, T.CONSTRUCTOR, T.CLASS_ONLY,
            T.OBJECT, T.INTERFACE, T.ENUM_CLASS, T.ANNOTATION_CLASS, T.TYPEALIAS,
        ),
        KtTokens.FUN_KEYWORD to setOf(T.INTERFACE),
        KtTokens.VALUE_KEYWORD to setOf(T.CLASS_ONLY),
    )

    private val POSSIBLE_PARENTS: Map<KtModifierKeywordToken, Set<T>> = mapOf(
        KtTokens.INNER_KEYWORD to setOf(T.CLASS_ONLY, T.LOCAL_CLASS, T.ENUM_CLASS, T.ENUM_ENTRY),
        KtTokens.OVERRIDE_KEYWORD to setOf(T.CLASS_ONLY, T.LOCAL_CLASS, T.OBJECT, T.OBJECT_LITERAL, T.INTERFACE, T.ENUM_CLASS, T.ENUM_ENTRY),
        KtTokens.PROTECTED_KEYWORD to setOf(T.CLASS_ONLY, T.LOCAL_CLASS, T.ENUM_CLASS, T.COMPANION_OBJECT),
        KtTokens.INTERNAL_KEYWORD to setOf(
            T.CLASS_ONLY, T.LOCAL_CLASS, T.OBJECT, T.OBJECT_LITERAL, T.ENUM_CLASS, T.ENUM_ENTRY, T.INTERFACE, T.FILE,
        ),
        KtTokens.PRIVATE_KEYWORD to setOf(
            T.CLASS_ONLY, T.LOCAL_CLASS, T.OBJECT, T.OBJECT_LITERAL, T.INTERFACE, T.ENUM_CLASS, T.ENUM_ENTRY, T.FILE,
        ),
        KtTokens.COMPANION_KEYWORD to setOf(T.CLASS_ONLY, T.INTERFACE, T.ENUM_CLASS, T.ANNOTATION_CLASS),
        KtTokens.FINAL_KEYWORD to setOf(
            T.CLASS_ONLY, T.LOCAL_CLASS, T.OBJECT, T.OBJECT_LITERAL, T.ENUM_CLASS, T.ENUM_ENTRY, T.ANNOTATION_CLASS, T.FILE,
        ),
        KtTokens.VARARG_KEYWORD to setOf(T.CONSTRUCTOR, T.FUNCTION, T.CLASS),
    )

    /** Groups whose members exclude one another (the compiler's `incompatibilityRegister` calls). */
    private val INCOMPATIBLE: List<Set<KtModifierKeywordToken>> = listOf(
        setOf(KtTokens.IN_KEYWORD, KtTokens.OUT_KEYWORD),
        setOf(KtTokens.PRIVATE_KEYWORD, KtTokens.PROTECTED_KEYWORD, KtTokens.PUBLIC_KEYWORD, KtTokens.INTERNAL_KEYWORD),
        setOf(KtTokens.ABSTRACT_KEYWORD, KtTokens.OPEN_KEYWORD, KtTokens.FINAL_KEYWORD, KtTokens.SEALED_KEYWORD),
        setOf(KtTokens.DATA_KEYWORD, KtTokens.OPEN_KEYWORD), setOf(KtTokens.DATA_KEYWORD, KtTokens.INNER_KEYWORD),
        setOf(KtTokens.DATA_KEYWORD, KtTokens.ABSTRACT_KEYWORD), setOf(KtTokens.DATA_KEYWORD, KtTokens.SEALED_KEYWORD),
        setOf(KtTokens.DATA_KEYWORD, KtTokens.INLINE_KEYWORD), setOf(KtTokens.DATA_KEYWORD, KtTokens.VALUE_KEYWORD),
        setOf(KtTokens.DATA_KEYWORD, KtTokens.OBJECT_KEYWORD, KtTokens.EXPECT_KEYWORD),
        setOf(KtTokens.CONST_KEYWORD, KtTokens.ABSTRACT_KEYWORD), setOf(KtTokens.CONST_KEYWORD, KtTokens.OPEN_KEYWORD),
        setOf(KtTokens.CONST_KEYWORD, KtTokens.OVERRIDE_KEYWORD), setOf(KtTokens.PRIVATE_KEYWORD, KtTokens.OVERRIDE_KEYWORD),
        setOf(KtTokens.CROSSINLINE_KEYWORD, KtTokens.NOINLINE_KEYWORD), setOf(KtTokens.SEALED_KEYWORD, KtTokens.INNER_KEYWORD),
        setOf(KtTokens.EXPECT_KEYWORD, KtTokens.ACTUAL_KEYWORD),
    )
}
