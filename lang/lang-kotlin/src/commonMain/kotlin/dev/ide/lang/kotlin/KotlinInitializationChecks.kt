package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassInitializer
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtDestructuringDeclaration
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtEnumEntry
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtFunctionType
import dev.ide.kotlin.syntax.psi.KtLambdaArgument
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtObjectLiteralExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtSuperTypeCallEntry
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeAlias
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.unwrapParen

/**
 * Checks about what may be touched while an object is still being built: an enum's companion object from the
 * enum's own initialization (UNINITIALIZED_ENUM_COMPANION), and the instance itself from a constructor's
 * header (INSTANCE_ACCESS_BEFORE_SUPER_CALL). Both are read off one file's tree: the declarations involved
 * (the enum and its companion, the class and its members) are always in the same file as the use.
 */
internal object KotlinInitializationChecks {

    // --- UNINITIALIZED_ENUM_COMPANION ----------------------------------------------------------------------

    /**
     * An enum's companion object read while the enum's entries are still being created.
     *
     * The JVM initializes an enum's entries before its companion, so anything that reaches the companion from
     * an entry's constructor arguments, an entry body's property initializers and `init` blocks, or the enum's
     * own property initializers and `init` blocks sees it uninitialized. K2 walks the class initialization
     * graph for these (FirEnumCompanionInEnumConstructorCallChecker): it follows lambdas called in place and
     * anonymous objects, and stops at functions, accessors, non-in-place lambdas and local classes, whose code
     * runs later. An access is anything whose receiver is the companion: a bare member (`foo()`), the
     * companion by name (`Companion.foo()`, `E.Companion`), the enum's name standing for it (`E.foo()`, `E()`
     * through an `invoke` operator), or a typealias to either.
     *
     * Only members DECLARED in the companion (or extensions written on it in this file) are known to live
     * there; an inherited one is left alone, as is a name anything nearer could shadow.
     */
    fun uninitializedEnumCompanion(cls: KtClass): List<Diagnostic> {
        if (!cls.isEnum() || cls is KtEnumEntry) return emptyList()
        val enumName = cls.name ?: return emptyList()
        val companion = cls.companionObjects.singleOrNull() ?: return emptyList()
        val companionName = companion.name ?: return emptyList()
        val file = cls.containingKtFile
        val ctx = EnumCompanionContext(cls, enumName, companion, companionName, file)
        val out = ArrayList<Diagnostic>()
        val seen = HashSet<Int>()
        fun scan(e: KtElement?) { if (e != null) ctx.visit(e) { at -> if (seen.add(at.textRange.startOffset)) out += ctx.diagnostic(at) } }
        for (entry in cls.enumEntries) {
            entry.initializerList?.initializers?.forEach { init ->
                (init as? KtSuperTypeCallEntry)?.valueArguments?.forEach { scan(it.getArgumentExpression()) }
            }
            scanInitializers(entry, ::scan)
        }
        scanInitializers(cls, ::scan)
        return out
    }

    /** The parts of [owner]'s body that run while it is constructed: property initializers and delegates, and
     *  `init` blocks. Nested classes, functions and accessors run later. */
    private fun scanInitializers(owner: KtClassOrObject, scan: (KtElement?) -> Unit) {
        for (d in owner.declarations) when (d) {
            is KtProperty -> { scan(d.initializer); scan(d.delegateExpression) }
            is KtClassInitializer -> scan(d.body)
            else -> {}
        }
    }

    private class EnumCompanionContext(
        val enumClass: KtClass,
        val enumName: String,
        val companion: KtObjectDeclaration,
        val companionName: String,
        val file: KtFile,
    ) {
        /** Callables declared in the companion's body. */
        val companionMembers: Set<String> = companion.declarations
            .filter { it is KtProperty || it is KtNamedFunction }
            .mapNotNull { (it as KtNamedDeclaration).name }.toHashSet()

        /** Classifiers nested in the companion: `Companion.Nested` names a type, it does not read the object. */
        val companionClassifiers: Set<String> = companion.declarations
            .filterIsInstance<KtClassOrObject>().mapNotNull { it.name }.toHashSet()

        /** typealias name -> true when it denotes the companion, false when it denotes the enum class. */
        val aliases: Map<String, Boolean> = run {
            val raw = file.declarations.filterIsInstance<KtTypeAlias>()
                .mapNotNull { a -> a.name?.let { n -> n to (a.typeReference?.text?.filterNot { it.isWhitespace() } ?: "") } }
                .toMap()
            val out = HashMap<String, Boolean>()
            fun denote(text: String, depth: Int): Boolean? = when {
                depth > 8 -> null
                text == enumName -> false
                text == "$enumName.$companionName" -> true
                text in raw -> denote(raw.getValue(text), depth + 1)
                else -> null
            }
            for ((n, t) in raw) denote(t, 0)?.let { out[n] = it }
            out
        }

        /** Top-level extensions written on the companion in this file (`fun E.Companion.ext()`). */
        val companionExtensions: Set<String> = file.declarations
            .filter { it is KtNamedFunction || it is KtProperty }
            .mapNotNull { d ->
                val recv = (d as dev.ide.kotlin.syntax.psi.KtCallableDeclaration).receiverTypeReference?.text
                    ?.filterNot { it.isWhitespace() } ?: return@mapNotNull null
                if (recv == "$enumName.$companionName" || aliases[recv] == true) d.name else null
            }.toHashSet()

        /** Top-level non-extension callables of this file, which an extension on the companion competes with. */
        val topLevelPlain: Set<String> = file.declarations
            .filter { (it is KtNamedFunction || it is KtProperty) && (it as dev.ide.kotlin.syntax.psi.KtCallableDeclaration).receiverTypeReference == null }
            .mapNotNull { (it as KtNamedDeclaration).name }.toHashSet()

        val hasInvoke: Boolean =
            companion.declarations.any { it is KtNamedFunction && it.name == "invoke" && it.hasModifier(KtTokens.OPERATOR_KEYWORD) } ||
                file.declarations.any { d ->
                    d is KtNamedFunction && d.name == "invoke" && d.hasModifier(KtTokens.OPERATOR_KEYWORD) &&
                        d.receiverTypeReference?.text?.filterNot { it.isWhitespace() }
                            ?.let { it == "$enumName.$companionName" || aliases[it] == true } == true
                }

        /** Names the enum's own scope reaches statically, never through the companion. */
        val staticNames: Set<String> = enumClass.enumEntries.mapNotNull { it.name }.toHashSet() +
            enumClass.declarations.filterIsInstance<KtClassOrObject>().mapNotNull { it.name } +
            setOf("values", "valueOf", "entries")

        fun diagnostic(at: KtElement): Diagnostic {
            val r = at.textRange
            return Diagnostic(
                TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                "Companion object of enum class '$enumName' is uninitialized here.",
                KotlinDiagnosticCodes.UNINITIALIZED_ENUM_COMPANION,
            )
        }

        fun visit(e: KtElement, report: (KtElement) -> Unit) {
            when (e) {
                is KtLambdaExpression -> { if (calledInPlace(e)) e.bodyExpression?.let { visit(it, report) }; return }
                is KtNamedFunction, is KtCallableReferenceExpression -> return
                is KtObjectLiteralExpression -> {
                    val obj = e.objectDeclaration ?: return
                    obj.superTypeListEntries.forEach { st ->
                        (st as? KtSuperTypeCallEntry)?.valueArguments?.forEach { a -> a.getArgumentExpression()?.let { visit(it, report) } }
                    }
                    scanInitializers(obj) { it?.let { x -> visit(x, report) } }
                    return
                }
                is KtClassOrObject -> return // a local class runs its code when it is instantiated, later
                is KtNameReferenceExpression -> { access(e)?.let(report); return }
                else -> {}
            }
            for (c in e.children) visit(c, report)
        }

        /** The element to report when [ref] reads the companion, else null. */
        private fun access(ref: KtNameReferenceExpression): KtElement? {
            val name = ref.getReferencedName()
            val parent = ref.parent
            val call = (parent as? KtCallExpression)?.takeIf { it.calleeExpression === ref }
            val node: KtExpression = call ?: ref
            val q = node.parent as? KtQualifiedExpression
            val isSelector = q != null && q.selectorExpression === node
            if (isSelector) {
                // `E.Companion` (or `Alias.Companion`): the selector names the companion object itself.
                val recv = unwrapParen(q!!.receiverExpression) as? KtNameReferenceExpression ?: return null
                if (name == companionName && denotesEnumClass(recv) && (call != null || !qualifiesClassifier(q))) return ref
                return null
            }
            if (q != null && q.receiverExpression === node && call == null) {
                // `Companion.x`, `Alias.x` and `E.x` where `x` lives in the companion.
                val selName = selectorName(q) ?: return null
                if (denotesCompanion(ref)) return if (selName in companionClassifiers) null else ref
                if (denotesEnumClass(ref)) {
                    if (selName in staticNames || selName == companionName) return null
                    return if (selName in companionMembers || selName in companionExtensions) ref else null
                }
                return null
            }
            // An unqualified use: the companion by name, an invoke on the enum's name, or a bare member.
            if (denotesCompanion(ref)) return ref
            if (call != null && denotesEnumClass(ref) && hasInvoke) return ref
            if (call == null && parent is KtUserType) return null
            // The enum's name used as a value stands for its companion object.
            if (call == null && q == null && denotesEnumClass(ref) && parent !is dev.ide.kotlin.syntax.psi.KtValueArgumentName &&
                parent !is dev.ide.kotlin.syntax.psi.KtClassLiteralExpression) return ref
            if (name in companionMembers || (name in companionExtensions && name !in topLevelPlain && name !in companionMembers)) {
                if (!shadowed(ref, name)) return ref
            }
            return null
        }

        private fun qualifiesClassifier(q: KtQualifiedExpression): Boolean {
            val outer = q.parent as? KtQualifiedExpression ?: return false
            if (outer.receiverExpression !== q) return false
            return selectorName(outer)?.let { it in companionClassifiers } == true
        }

        private fun selectorName(q: KtQualifiedExpression): String? = when (val s = q.selectorExpression) {
            is KtNameReferenceExpression -> s.getReferencedName()
            is KtCallExpression -> (s.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
            else -> null
        }

        /** [ref] (unqualified) names the companion: its own name or an alias to it, not shadowed. */
        private fun denotesCompanion(ref: KtNameReferenceExpression): Boolean {
            if (ref.parent is KtQualifiedExpression && (ref.parent as KtQualifiedExpression).selectorExpression === ref) return false
            val name = ref.getReferencedName()
            if (name != companionName && aliases[name] != true) return false
            return !shadowed(ref, name)
        }

        private fun denotesEnumClass(ref: KtNameReferenceExpression): Boolean {
            val name = ref.getReferencedName()
            if (name != enumName && aliases[name] != false) return false
            return !shadowed(ref, name)
        }

        /**
         * Whether something nearer than the companion answers to [name] at [ref]: a local, a lambda or
         * constructor parameter, or a member of an enclosing class body (an entry, an anonymous object, the
         * enum itself, which also inherits `Enum`'s members). A body with supertypes this tree cannot list
         * shadows everything.
         */
        private fun shadowed(ref: KtElement, name: String): Boolean {
            if (name in ENUM_MEMBERS) return true
            var node: KtElement? = ref.parent
            var child: KtElement = ref
            while (node != null) {
                when (node) {
                    is KtBlockExpression -> if (node.statements.any { declares(it, name) }) return true
                    is KtFunctionLiteral -> {
                        if (node.valueParameters.any { it.name == name || it.destructuringDeclaration?.entries?.any { e -> e.name == name } == true }) return true
                        if (name == "it" && node.valueParameters.isEmpty()) return true
                    }
                    is KtClassOrObject -> {
                        if (node.declarations.any { it !is KtObjectDeclaration && it !is KtEnumEntry && (it as? KtNamedDeclaration)?.name == name }) return true
                        if (node.primaryConstructorParameters.any { it.name == name }) return true
                        if (node === enumClass) {
                            if (enumClass.superTypeListEntries.isNotEmpty()) return true
                            return false
                        }
                        if (node is KtEnumEntry) { node = enumClass; child = node; continue }
                        if (node.superTypeListEntries.isNotEmpty()) return true
                    }
                    else -> {}
                }
                child = node
                node = node.parent
            }
            return true
        }

        private fun declares(st: KtElement, name: String): Boolean = when (st) {
            is KtNamedDeclaration -> st.name == name
            is KtDestructuringDeclaration -> st.entries.any { it.name == name }
            else -> false
        }

        private fun calledInPlace(lambda: KtLambdaExpression): Boolean = calledInPlaceIn(file, lambda)

    }

    // --- INSTANCE_ACCESS_BEFORE_SUPER_CALL -----------------------------------------------------------------

    /**
     * The instance used in a constructor's header, before the superclass constructor has run: in a primary or
     * secondary constructor's parameter defaults, a secondary constructor's `: this(…)` / `: super(…)`
     * arguments, or the arguments of the superclass call in the supertype list (an object's or a companion's
     * included). There `this`, `super`, and any member of the class reached through the implicit `this`
     * (`foo()`, `prop`, `Inner()`) are rejected; the constructor's own parameters, the companion, nested
     * classes and anything outside the class are fine.
     *
     * Only members DECLARED in this class (or in a superclass declared in this file) are known to be members;
     * lambdas and object literals are left alone, since their own receivers can change what a name means.
     */
    fun instanceAccessBeforeSuperCall(cls: KtClassOrObject): List<Diagnostic> {
        if (cls is KtEnumEntry) return emptyList()
        if (cls is KtObjectDeclaration && cls.isObjectLiteral()) return emptyList()
        if (cls is KtClass && (cls.isInterface() || cls.isAnnotation())) return emptyList()
        // An inner or local class has an outer receiver, which is what `this` and an inherited name fall back
        // to while the class's own instance is unavailable: K2 resolves them there instead of reporting.
        if (cls is KtClass && cls.isInner()) return emptyList()
        if (isLocalOrInInner(cls)) return emptyList()
        // Most classes have no header expression at all; the member index is built only for one that does.
        val hasHeaderCode = cls.primaryConstructorParameters.any { it.defaultValue != null } ||
            cls.superTypeListEntries.any { (it is KtSuperTypeCallEntry && it.valueArguments.isNotEmpty()) || it is dev.ide.kotlin.syntax.psi.KtDelegatedSuperTypeEntry } ||
            cls.secondaryConstructors.isNotEmpty()
        if (!hasHeaderCode) return emptyList()
        val members by lazy { instanceMemberNames(cls) }
        val out = ArrayList<Diagnostic>()
        val primaryParams = cls.primaryConstructorParameters.mapNotNull { it.name }.toHashSet()
        fun scan(e: KtElement?, params: Set<String>) {
            if (e != null) headerAccess(e, cls, { members }, params) { at -> out += instanceAccessDiagnostic(at) }
        }
        for (p in cls.primaryConstructorParameters) scan(p.defaultValue, primaryParams)
        for (st in cls.superTypeListEntries) {
            if (st is KtSuperTypeCallEntry) st.valueArguments.forEach { scan(it.getArgumentExpression(), primaryParams) }
            // An object's delegate reaches the object's members statically, which K2 accepts.
            if (st is dev.ide.kotlin.syntax.psi.KtDelegatedSuperTypeEntry && cls is KtClass) scan(st.delegateExpression, primaryParams)
        }
        for (ctor in cls.secondaryConstructors) {
            val params = ctor.valueParameters.mapNotNull { it.name }.toHashSet()
            for (p in ctor.valueParameters) scan(p.defaultValue, params)
            val delegation = ctor.getDelegationCall()
            if (!delegation.isImplicit) delegation.valueArguments.forEach { scan(it.getArgumentExpression(), params) }
        }
        return out.distinctBy { it.range.start }
    }

    private fun instanceAccessDiagnostic(at: KtElement): Diagnostic {
        val r = at.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Cannot access '<this>' before the instance has been initialized.",
            KotlinDiagnosticCodes.INSTANCE_ACCESS_BEFORE_SUPER_CALL,
        )
    }

    /**
     * The names [cls]'s implicit `this` answers to: its own non-extension properties and functions, its
     * `val`/`var` constructor parameters and inner classes, and the same of every superclass declared in this
     * file (followed by simple name). Not its companion's members or its nested (non-inner) classes.
     */
    private fun instanceMemberNames(cls: KtClassOrObject): Set<String> {
        val out = HashSet<String>()
        val fileClasses = HashMap<String, KtClassOrObject>()
        fun index(decls: List<KtDeclaration>) {
            for (d in decls) if (d is KtClassOrObject && d !is KtEnumEntry) {
                d.name?.let { fileClasses.getOrPut(it) { d } }
                index(d.declarations)
            }
        }
        index(cls.containingKtFile.declarations)
        val visited = HashSet<KtClassOrObject>()
        fun collect(c: KtClassOrObject) {
            if (!visited.add(c)) return
            for (d in c.declarations) when (d) {
                is KtProperty -> if (d.receiverTypeReference == null) d.name?.let { out += it }
                is KtNamedFunction -> if (d.receiverTypeReference == null) d.name?.let { out += it }
                is KtClass -> if (d.isInner()) d.name?.let { out += it }
                else -> {}
            }
            c.primaryConstructorParameters.filter { it.hasValOrVar() }.mapNotNull { it.name }.forEach { out += it }
            for (st in c.superTypeListEntries) {
                val name = (st.typeReference?.typeElement as? KtUserType)?.referencedName ?: continue
                fileClasses[name]?.let { collect(it) }
            }
        }
        collect(cls)
        // A companion member of the same name wins in the header, where the instance is not available.
        cls.companionObjects.forEach { c -> c.declarations.forEach { d -> (d as? KtNamedDeclaration)?.name?.let { out -= it } } }
        return out
    }

    private fun isLocalOrInInner(cls: KtClassOrObject): Boolean {
        var node = cls.parent
        while (node != null) {
            if (node is KtBlockExpression || node is KtNamedFunction || node is dev.ide.kotlin.syntax.psi.KtFunctionLiteral) return true
            if (node is KtClass && node.isInner()) return true
            node = node.parent
        }
        return false
    }

    private fun headerAccess(
        e: KtElement, cls: KtClassOrObject, members: () -> Set<String>, params: Set<String>, report: (KtElement) -> Unit,
    ) {
        when (e) {
            is KtLambdaExpression, is KtObjectLiteralExpression, is KtNamedFunction, is KtCallableReferenceExpression,
            is KtClassOrObject -> return
            is dev.ide.kotlin.syntax.psi.KtThisExpression -> {
                val label = e.getLabelName()
                if (label == null || label == cls.name) report(e)
                return
            }
            is dev.ide.kotlin.syntax.psi.KtSuperExpression -> {
                val label = e.getLabelName()
                if (e.superTypeQualifier == null && (label == null || label == cls.name)) report(e)
                return
            }
            is KtNameReferenceExpression -> {
                val name = e.getReferencedName()
                if (name in params || name !in members()) return
                val parent = e.parent
                if (parent is dev.ide.kotlin.syntax.psi.KtValueArgumentName || parent is KtUserType) return
                val node: KtElement = (parent as? KtCallExpression)?.takeIf { it.calleeExpression === e } ?: e
                val q = node.parent as? KtQualifiedExpression
                if (q != null && q.selectorExpression === node) return
                report(e)
                return
            }
            else -> {}
        }
        for (c in e.children) headerAccess(c, cls, members, params, report)
    }

    /** What every enum inherits from `kotlin.Enum` (and `Any`): an instance member that beats the companion. */
    private val ENUM_MEMBERS = setOf(
        "name", "ordinal", "compareTo", "equals", "hashCode", "toString", "getDeclaringClass", "declaringJavaClass",
        "describeConstable", "finalize", "clone",
    )
}

/**
 * A lambda K2 knows is called in place: passed to a function of this file whose contract says
 * `callsInPlace` for that parameter (a function type with no receiver, so the lambda's scope is
 * unchanged), or to one of the standard library's in-place functions whose lambda adds no receiver.
 */
internal fun calledInPlaceIn(file: KtFile, lambda: KtLambdaExpression): Boolean {
    var arg: KtElement = lambda
    while (arg.parent is dev.ide.kotlin.syntax.psi.KtParenthesizedExpression || arg.parent is dev.ide.kotlin.syntax.psi.KtLabeledExpression) arg = arg.parent!!
    val va = arg.parent as? KtValueArgument ?: return false
    val call = (if (va is KtLambdaArgument) va.parent else va.parent?.parent) as? KtCallExpression ?: return false
    val callee = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return false
    val qualified = (call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === call } != null
    val declared = file.declarations.filterIsInstance<KtNamedFunction>().filter { it.name == callee }
    if (declared.isEmpty()) {
        if (localDeclaresFunction(call, callee)) return false
        return when (callee) {
            "run", "repeat" -> !qualified
            "let", "also", "takeIf", "takeUnless" -> qualified
            else -> false
        }
    }
    val fn = declared.singleOrNull() ?: return false
    val params = fn.valueParameters
    val idx = when {
        va is KtLambdaArgument -> params.lastIndex
        va.getArgumentName() != null -> params.indexOfFirst { it.name == va.getArgumentName()?.asName?.identifier }
        else -> call.valueArgumentList?.arguments?.indexOf(va) ?: -1
    }
    val p = params.getOrNull(idx) ?: return false
    if (p.typeReference?.typeElement.let { it !is KtFunctionType || it.receiverTypeReference != null }) return false
    val body = fn.bodyBlockExpression ?: return false
    val contract = body.statements.firstOrNull() as? KtCallExpression ?: return false
    if ((contract.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() != "contract") return false
    val pName = p.name ?: return false
    return Regex("""\bcallsInPlace\s*\(\s*${Regex.escape(pName)}\b""").containsMatchIn(contract.text)
}

private fun localDeclaresFunction(from: KtElement, name: String): Boolean {
    var node: KtElement? = from.parent
    while (node != null) {
        if (node is KtBlockExpression && node.statements.any { it is KtNamedFunction && it.name == name }) return true
        if (node is KtClassOrObject && node.declarations.any { it is KtNamedFunction && it.name == name }) return true
        node = node.parent
    }
    return false
}
