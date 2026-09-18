package dev.ide.kotlin.syntax.psi

import com.intellij.platform.syntax.SyntaxElementType
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes
import org.jetbrains.kotlin.kmp.tree.LightNode
import org.jetbrains.kotlin.kmp.utils.SyntaxElementTypesWithIds

/**
 * The typed view of the parse tree, mirroring `org.jetbrains.kotlin.psi.KtElement` and its subclasses.
 *
 * This is the part of the module that is ours, and the only part that is now load-bearing. The grammar is
 * vendored from the compiler and needs no reimplementing; what the editor backend speaks is PSI — 130 `Kt*`
 * types over ~3,000 references — while the vendored parser produces a flat `LightSyntaxTree`. Bridging those
 * is the actual work, and the accessor names here are the compiler's for exactly that reason:
 * `initializer`, `bodyExpression`, `receiverTypeReference`, `getReferencedName`. A call site written against
 * `org.jetbrains.kotlin.psi` should need an import change and little else.
 *
 * Two facts about the underlying tree leak through and are worth knowing:
 *
 *  * An element is `(session, node)`, never a node alone: `LightNode` is an index into flat arrays and does
 *    not know its tree. See [KtTreeSession].
 *  * The parser COLLAPSES some markers — every modifier keyword, and the operators the new lexer leaves in
 *    pieces — so a modifier is a composite named after its token kind. Type queries are unaffected, which is
 *    why [hasModifier] reads the same here as it would over PSI.
 */
open class KtElement internal constructor(
    @PublishedApi internal val session: KtTreeSession,
    @PublishedApi internal val node: LightNode,
) : KtPsiElement {

    override val elementType: SyntaxElementType get() = session.tree.getType(node)

    override val text: String get() = session.tree.getText(node).toString()

    override val textOffset: Int get() = session.tree.getStartOffset(node) + session.baseOffset

    override val textLength: Int
        get() = session.tree.getEndOffset(node) - session.tree.getStartOffset(node)

    override val textRange: TextRange
        get() = TextRange(
            session.tree.getStartOffset(node) + session.baseOffset,
            session.tree.getEndOffset(node) + session.baseOffset,
        )

    override val parent: KtElement? get() = session.tree.getParent(node)?.let { session.psi(it) }

    /**
     * Children, trivia excluded, computed once per element.
     *
     * Cached because this is THE hot path: every accessor on every subclass is a filter over it, and so is
     * every ancestor walk and every descendant walk. Recomputing it allocated two lists per call (the
     * filtered nodes, then the wrappers), which measured as a 14x allocation regression on member-access
     * completion against the PSI implementation, where children were a linked list that allocated nothing.
     *
     * Bounded by the tree, not by traffic: the session already keeps one wrapper per node, so this adds one
     * list per node that anything actually looked at.
     */
    override val children: List<KtElement>
        get() = childCache ?: session.childrenOf(node).map { session.psi(it) }.also { childCache = it }

    private var childCache: List<KtElement>? = null

    /**
     * Children INCLUDING whitespace and comments, which [children] leaves out.
     *
     * Almost nothing wants these: an accessor asking for "the declarations" would have to filter them back out
     * every time, which is why [children] drops them. A caret query does want them, because a comment is
     * somewhere the caret can be, and asking what is at an offset through the trivia-free view answers with
     * whatever ENCLOSES the comment instead of the comment.
     */
    val childrenWithTrivia: List<KtElement> get() = session.tree.getChildren(node).map { session.psi(it) }

    /**
     * The doc-comment tokens directly under this element, and empty for almost every element there is.
     *
     * The emptiness is the point: a consumer that wants doc comments has to look through [childrenWithTrivia],
     * and doing that per node on a hot walk materialises a list for every node in the file to find the handful
     * that carry one. `findChildByType` answers the common case without allocating anything.
     */
    val docCommentChildren: List<KtElement>
        get() = if (!hasChild(KtTokens.DOC_COMMENT)) emptyList()
        else childrenWithTrivia.filter { it.elementType == KtTokens.DOC_COMMENT }

    val firstChild: KtElement? get() = children.firstOrNull()

    val lastChild: KtElement? get() = children.lastOrNull()

    val nextSibling: KtElement? get() = sibling(1)

    val prevSibling: KtElement? get() = sibling(-1)

    private fun sibling(step: Int): KtElement? {
        val parentNode = session.tree.getParent(node) ?: return null
        val siblings = session.childrenOf(parentNode)
        val index = siblings.indexOfFirst { it.index == node.index }
        if (index < 0) return null
        return siblings.getOrNull(index + step)?.let { session.psi(it) }
    }

    /**
     * A single token rather than a composite.
     *
     * Worth having because PSI answered this by TYPE: a leaf was a `LeafPsiElement` and only composites
     * implemented `KtElement`, so `x is KtElement` meant "not a token". Here everything in the tree is a
     * [KtElement], so a consumer that wants only composites (projecting the tree into a neutral DOM, say) has
     * to ask, and the version that did not ask silently represented every keyword and brace as a node.
     */
    val isToken: Boolean get() = session.tree.isToken(node)

    /**
     * Semantically ONE token, whether the tree stores it as a leaf or as a collapsed marker.
     *
     * `marker.collapse(type)` yields a leaf in PSI but a composite wrapping its tokens here, and the parser
     * runs it over every modifier keyword and over the operators the new lexer leaves in pieces: `!!` is
     * `(EXCLEXCL EXCL EXCL)`, and so are `?:` and `?.`. A collapsed marker is recognisable without a list,
     * because nothing else is a composite NAMED AFTER A TOKEN KIND holding only tokens, and
     * `KtTokens.getElementTypeId` answers whether a kind is one of its own.
     *
     * A consumer projecting the tree (the neutral DOM) wants these to be leaves: representing `open`, `!!`
     * and every annotation keyword as nodes of their own is the difference between a DOM of the code's shapes
     * and one with a node per keystroke.
     */
    val isTokenLike: Boolean
        get() = isToken || (
            KtTokens.getElementTypeId(elementType) != SyntaxElementTypesWithIds.NO_ID &&
                children.isNotEmpty() && children.all { it.isToken }
            )

    /** A region the parser could not read: the error marker it recovered at, if this is one. */
    val isErrorElement: Boolean
        get() = elementType == com.intellij.platform.syntax.element.SyntaxTokenTypes.ERROR_ELEMENT

    /** The file this element belongs to. */
    override val containingKtFile: KtFile get() = session.file

    /** The same file under the name `PsiElement` gives it. */
    val containingFile: KtFile get() = session.file

    internal fun child(type: SyntaxElementType): KtElement? =
        session.tree.findChildByType(node, type)?.let { session.psi(it) }

    internal fun hasChild(type: SyntaxElementType): Boolean =
        session.tree.findChildByType(node, type) != null

    internal inline fun <reified T : KtElement> firstChildOfType(): T? = children.firstOrNull { it is T } as T?

    internal inline fun <reified T : KtElement> childrenOfType(): List<T> = children.filterIsInstance<T>()

    /** Does this element carry [token] in its modifier list? */
    open fun hasModifier(token: SyntaxElementType): Boolean = modifierList?.hasModifier(token) == true

    open val modifierList: KtModifierList? get() = firstChildOfType()

    open val annotationEntries: List<KtAnnotationEntry> get() = modifierList?.annotationEntries.orEmpty()

    /**
     * The doc comment for this element, if any.
     *
     * It is looked for INSIDE the element first, because the compiler's comment binders pull a preceding doc
     * comment into the declaration it documents — that is what `KotlinWhitespaceAndCommentsBinders` is for,
     * and it is the behaviour the hand-written parser here did not have. The outside case is still checked
     * for elements no binder claims.
     *
     * Whitespace is skipped, and so are ZERO-WIDTH siblings: a file always carries a package directive and an
     * import list even when it has neither, and those empty elements sit between a comment and the first
     * declaration.
     */
    val docComment: KtElement?
        get() = leadingDocComment(session.tree.getChildren(node), 0, step = 1)
            ?: session.tree.getParent(node)?.let { parentNode ->
                val siblings = session.tree.getChildren(parentNode)
                val index = siblings.indexOfFirst { it.index == node.index }
                if (index <= 0) null else leadingDocComment(siblings, index - 1, step = -1)
            }

    private fun leadingDocComment(nodes: List<LightNode>, from: Int, step: Int): KtElement? {
        var index = from
        while (index in nodes.indices) {
            val candidate = nodes[index]
            val type = session.tree.getType(candidate)
            if (type == KtTokens.DOC_COMMENT) return session.psi(candidate)
            val empty = session.tree.getEndOffset(candidate) == session.tree.getStartOffset(candidate)
            if (type != KtTokens.WHITE_SPACE && !empty) return null
            index += step
        }
        return null
    }

    override fun toString(): String = "$elementType$textRange"

    override fun equals(other: Any?): Boolean =
        other is KtElement && other.session === session && other.node.index == node.index

    override fun hashCode(): Int = node.index
}

/** An element with a name, mirroring `KtNamedDeclaration`. */
abstract class KtNamedDeclaration internal constructor(session: KtTreeSession, node: LightNode) :
    KtDeclaration(session, node) {

    /** The identifier token, or null when the name is missing (half-typed code, or an anonymous object). */
    open val nameIdentifier: KtElement? get() = child(KtTokens.IDENTIFIER)

    open val name: String? get() = nameIdentifier?.text?.removeSurrounding("`")

    val nameAsName: Name? get() = name?.let { Name.identifier(it) }

    /** The name, or `<no name provided>` for an anonymous declaration, as upstream spells it. */
    val nameAsSafeName: Name get() = nameAsName ?: Name.identifier("<no name provided>")

    /**
     * The fully qualified name, or null for a local or anonymous declaration.
     *
     * Built by walking out through the containing classes to the package directive, which is the only way
     * available here: upstream reads it off a stub when there is one, and computes exactly this when there
     * is not.
     */
    val fqName: FqName?
        get() {
            val own = name ?: return null
            val segments = mutableListOf(own)
            // Walk the REAL parent chain rather than hopping containing classes. Only a class body or a
            // class/object may stand between a declaration and its file. Anything else (a function body, an
            // initializer, a property's initializer) makes the declaration LOCAL, and a local declaration has
            // no qualified name. Hopping `containingClassOrObject` cannot see that: it answers null both for a
            // top-level declaration and for one nested inside a function, so a local class came out with a
            // package-qualified name that nothing else in the IDE agrees exists.
            var current: KtElement? = parent
            while (current != null && current !is KtFile) {
                when (current) {
                    is KtClassBody -> Unit
                    is KtClassOrObject -> segments += current.name ?: return null
                    else -> return null
                }
                current = current.parent
            }
            if (current == null) return null
            val packageName = containingKtFile.packageFqName
            if (!packageName.isRoot) segments += packageName.asString()
            return FqName(segments.asReversed().joinToString("."))
        }
}

/** Anything that declares something, mirroring `KtDeclaration`. */
abstract class KtDeclaration internal constructor(session: KtTreeSession, node: LightNode) :
    KtExpression(session, node), KtAnnotated, KtModifierListOwner

/** A declaration that can have a receiver, type parameters and value parameters. */
abstract class KtCallableDeclaration internal constructor(session: KtTreeSession, node: LightNode) :
    KtNamedDeclaration(session, node), KtTypeParameterListOwner {

    override val typeParameterList: KtTypeParameterList? get() = firstChildOfType()

    override val typeParameters: List<KtTypeParameter> get() = typeParameterList?.parameters.orEmpty()

    open val valueParameterList: KtParameterList? get() = firstChildOfType()

    open val valueParameters: List<KtParameter> get() = valueParameterList?.parameters.orEmpty()

    /**
     * The extension receiver, or null.
     *
     * A callable has at most two type references directly under it — the receiver and the return type — and
     * the receiver is the one that comes BEFORE the name. Position is what separates them, not shape.
     */
    val receiverTypeReference: KtTypeReference?
        get() {
            val nameOffset = nameIdentifier?.textOffset ?: return null
            return childrenOfType<KtTypeReference>().firstOrNull { it.textOffset < nameOffset }
        }

    /** The declared return type, or null when it is inferred. */
    open val typeReference: KtTypeReference?
        get() {
            val nameOffset = nameIdentifier?.textOffset
                ?: return childrenOfType<KtTypeReference>().firstOrNull()
            return childrenOfType<KtTypeReference>().firstOrNull { it.textOffset > nameOffset }
        }

    override val typeConstraintList: KtTypeConstraintList? get() = firstChildOfType()

    override val typeConstraints: List<KtTypeConstraint> get() = typeConstraintList?.constraints.orEmpty()
}

// ---------------------------------------------------------------------------------------------------------
// File structure
// ---------------------------------------------------------------------------------------------------------

/** A parsed Kotlin file, mirroring `KtFile`. */
class KtFile internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node), KtAnnotated {

    /** The file name, carried through the parse; nothing in the text says what file it came from. */
    val name: String get() = session.fileName

    val packageDirective: KtPackageDirective? get() = firstChildOfType()

    /** The declared package, or the root for the default package. */
    val packageFqName: FqName get() = FqName(packageDirective?.qualifiedName ?: "")

    val importList: KtImportList? get() = firstChildOfType()

    val importDirectives: List<KtImportDirective> get() = importList?.imports.orEmpty()

    /** Top-level declarations, in source order. */
    val declarations: List<KtDeclaration> get() = childrenOfType()

    val fileAnnotationList: KtFileAnnotationList? get() = firstChildOfType()

    /** `@file:JvmName(...)`, which lives on its own list rather than on a modifier list. */
    override val annotationEntries: List<KtAnnotationEntry> get() = fileAnnotationList?.annotationEntries.orEmpty()
}

class KtPackageDirective internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node) {
    /** `a.b.c`, or the empty string when the directive is absent. */
    val qualifiedName: String
        get() = children.firstOrNull { it is KtExpression }?.text?.filterNot { it.isWhitespace() } ?: ""
}

class KtImportList internal constructor(session: KtTreeSession, node: LightNode) : KtElement(session, node) {
    val imports: List<KtImportDirective> get() = childrenOfType()
}

class KtImportDirective internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node) {

    /** The `a.b.C` of `import a.b.C as D`, as an expression. */
    val importedReference: KtExpression? get() = children.firstOrNull { it is KtExpression } as? KtExpression

    /** `a.b.C` from `import a.b.C as D`, without the star. */
    val importedFqName: FqName?
        get() = importedReference?.text?.filterNot { it.isWhitespace() }?.let { FqName(it) }

    val isAllUnder: Boolean get() = hasChild(KtTokens.MUL)

    val alias: KtImportAlias? get() = firstChildOfType()

    /**
     * The alias in `import a.b.C as D`, or null when there is none.
     *
     * Null rather than the short name, which is what upstream answers and what every caller assumes: they all
     * spell it `aliasName ?: fqn.shortName()`. Falling back here instead made every plain import look aliased,
     * and the import organizer duly rewrote `import a.A` as `import a.A as A`.
     */
    val aliasName: String? get() = alias?.name
}

class KtImportAlias internal constructor(session: KtTreeSession, node: LightNode) : KtElement(session, node) {
    val nameIdentifier: KtElement? get() = child(KtTokens.IDENTIFIER)
    val name: String? get() = nameIdentifier?.text
}

// ---------------------------------------------------------------------------------------------------------
// Classifiers
// ---------------------------------------------------------------------------------------------------------

/** A class, interface or object, mirroring `KtClassOrObject`. */
abstract class KtClassOrObject internal constructor(session: KtTreeSession, node: LightNode) :
    KtNamedDeclaration(session, node), KtTypeParameterListOwner {

    val body: KtClassBody? get() = firstChildOfType()

    /** Members declared in the body, enum entries included (they are declarations, as upstream has them). */
    val declarations: List<KtDeclaration> get() = body?.declarations.orEmpty()

    val primaryConstructor: KtPrimaryConstructor? get() = firstChildOfType()

    val secondaryConstructors: List<KtSecondaryConstructor>
        get() = body?.childrenOfType<KtSecondaryConstructor>().orEmpty()

    val superTypeList: KtSuperTypeList? get() = firstChildOfType()

    val superTypeListEntries: List<KtSuperTypeListEntry> get() = superTypeList?.entries.orEmpty()

    override val typeParameterList: KtTypeParameterList? get() = firstChildOfType()

    override val typeParameters: List<KtTypeParameter> get() = typeParameterList?.parameters.orEmpty()

    override val typeConstraintList: KtTypeConstraintList? get() = firstChildOfType()

    override val typeConstraints: List<KtTypeConstraint> get() = typeConstraintList?.constraints.orEmpty()

    val primaryConstructorParameters: List<KtParameter> get() = primaryConstructor?.valueParameters.orEmpty()

    /**
     * Declared `data`. Here rather than on [KtClass] because that is where PSI puts it, and a caller holding a
     * [KtClassOrObject] asks without narrowing first. An object cannot be `data`, so it answers false.
     */
    fun isData(): Boolean = hasModifier(KtTokens.DATA_MODIFIER)

    /**
     * True only when the constructor is WRITTEN.
     *
     * `class Foo` has a primary constructor and no `KtPrimaryConstructor` node, so a check that asked whether
     * the node exists and one that asked whether the class has a primary constructor are different questions
     * with the same obvious-looking spelling.
     */
    fun hasExplicitPrimaryConstructor(): Boolean = primaryConstructor != null

    fun hasPrimaryConstructor(): Boolean = hasExplicitPrimaryConstructor() || !hasSecondaryConstructors()

    fun hasSecondaryConstructors(): Boolean = secondaryConstructors.isNotEmpty()

    fun getAnonymousInitializers(): List<KtClassInitializer> = body?.childrenOfType<KtClassInitializer>().orEmpty()

    /** Companions declared in the body. A class can only have one, but the tree can hold a malformed two. */
    val companionObjects: List<KtObjectDeclaration>
        get() = body?.childrenOfType<KtObjectDeclaration>()?.filter { it.isCompanion() }.orEmpty()
}

open class KtClass internal constructor(session: KtTreeSession, node: LightNode) :
    KtClassOrObject(session, node) {

    // Functions, not properties. `isElse` on a when-entry is a property upstream and `isInterface()` on a
    // class is a method, so the call sites differ by a pair of parentheses and there is no rule to infer it
    // from: each one follows whatever upstream declared.
    fun isInterface(): Boolean = hasChild(KtTokens.INTERFACE_KEYWORD)

    fun isEnum(): Boolean = hasModifier(KtTokens.ENUM_MODIFIER)

    fun isAnnotation(): Boolean = hasModifier(KtTokens.ANNOTATION_MODIFIER)

    fun isSealed(): Boolean = hasModifier(KtTokens.SEALED_MODIFIER)

    fun isInner(): Boolean = hasModifier(KtTokens.INNER_MODIFIER)

    fun isValue(): Boolean = hasModifier(KtTokens.VALUE_MODIFIER)

    val enumEntries: List<KtEnumEntry> get() = body?.childrenOfType<KtEnumEntry>().orEmpty()
}

class KtObjectDeclaration internal constructor(session: KtTreeSession, node: LightNode) :
    KtClassOrObject(session, node) {
    /** A companion object has the `companion` modifier and usually no name. */
    fun isCompanion(): Boolean = hasModifier(KtTokens.COMPANION_MODIFIER)

    /**
     * `companion object { }` is named `Companion`, which is how it is addressed and how upstream reports it.
     *
     * Without this an unnamed companion has no name, so it has no [fqName] either, and everything keyed on a
     * companion's qualified name (importing through it, resolving `Outer.member`, its extensions) misses.
     */
    override val name: String?
        get() = super.name ?: if (isCompanion()) "Companion" else null

    fun isObjectLiteral(): Boolean = parent is KtObjectLiteralExpression

    val objectKeyword: KtElement? get() = child(KtTokens.OBJECT_KEYWORD)
}

class KtClassBody internal constructor(session: KtTreeSession, node: LightNode) : KtElement(session, node) {
    /**
     * Every declaration in the body, an enum's ENTRIES included.
     *
     * A `KtEnumEntry` is a declaration upstream and is returned here for the same reason: the backend finds an
     * enum's constants by walking declarations, so filtering them out left every enum with no constants, and
     * `E.A` unresolved everywhere it appeared. [KtClass.enumEntries] is the typed filter for callers that want
     * only the entries.
     */
    val declarations: List<KtDeclaration> get() = childrenOfType()
    val properties: List<KtProperty> get() = childrenOfType()
    val functions: List<KtNamedFunction> get() = childrenOfType()
    val lBrace: KtElement? get() = child(KtTokens.LBRACE)
    val rBrace: KtElement? get() = child(KtTokens.RBRACE)
}

/**
 * `A(1)` inside an enum. Upstream this extends [KtClass], not [KtNamedDeclaration], because an entry can
 * declare a body with members of its own and everything that walks a classifier has to reach those.
 */
class KtEnumEntry internal constructor(session: KtTreeSession, node: LightNode) :
    KtClass(session, node) {
    val initializerList: KtInitializerList? get() = firstChildOfType()
    fun hasInitializer(): Boolean = initializerList != null
}

class KtPrimaryConstructor internal constructor(session: KtTreeSession, node: LightNode) :
    KtDeclaration(session, node), KtFunction {
    override val valueParameterList: KtParameterList? get() = firstChildOfType()
    override val valueParameters: List<KtParameter> get() = valueParameterList?.parameters.orEmpty()

    /** A primary constructor never has a body; its initialization lives in the class's initializers. */
    override val equalsToken: KtElement? get() = null
    override val bodyExpression: KtExpression? get() = null
    override val bodyBlockExpression: KtBlockExpression? get() = null
    override fun hasBlockBody(): Boolean = false
    override fun hasBody(): Boolean = false
}

class KtSecondaryConstructor internal constructor(session: KtTreeSession, node: LightNode) :
    KtDeclaration(session, node), KtFunction {
    override val valueParameterList: KtParameterList? get() = firstChildOfType()
    override val valueParameters: List<KtParameter> get() = valueParameterList?.parameters.orEmpty()
    override val bodyExpression: KtBlockExpression? get() = firstChildOfType()
    override val bodyBlockExpression: KtBlockExpression? get() = bodyExpression
    override val equalsToken: KtElement? get() = null
    override fun hasBlockBody(): Boolean = bodyExpression != null
    override fun hasBody(): Boolean = bodyExpression != null

    /**
     * `: this(...)` or `: super(...)`.
     *
     * Non-null as upstream, and the reason is the interesting part: a constructor that delegates IMPLICITLY
     * still gets a node, a zero-width one, which is what [KtConstructorDelegationCall.isImplicit] reads. A
     * nullable accessor would push that distinction onto every call site.
     */
    fun getDelegationCall(): KtConstructorDelegationCall = firstChildOfType()!!
}

class KtClassInitializer internal constructor(session: KtTreeSession, node: LightNode) :
    KtDeclaration(session, node), KtAnonymousInitializer {
    override val body: KtBlockExpression? get() = firstChildOfType()
}

class KtSuperTypeList internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node) {
    val entries: List<KtSuperTypeListEntry> get() = childrenOfType()
}

abstract class KtSuperTypeListEntry internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node) {
    open val typeReference: KtTypeReference? get() = firstChildOfType()
}

class KtSuperTypeEntry internal constructor(session: KtTreeSession, node: LightNode) :
    KtSuperTypeListEntry(session, node)

class KtSuperTypeCallEntry internal constructor(session: KtTreeSession, node: LightNode) :
    KtSuperTypeListEntry(session, node) {
    val valueArgumentList: KtValueArgumentList? get() = firstChildOfType()
    val valueArguments: List<KtValueArgument> get() = valueArgumentList?.arguments.orEmpty()

    val calleeExpression: KtConstructorCalleeExpression? get() = firstChildOfType()

    /**
     * `Base()` in `class Child : Base()`.
     *
     * A called supertype wraps its type in a CONSTRUCTOR_CALLEE, so the type reference is a grandchild here
     * where a plain `KtSuperTypeEntry` has it as a direct child. Inheriting the base's direct-child lookup
     * silently returns null for every supertype that is CONSTRUCTED, which is every superclass.
     */
    override val typeReference: KtTypeReference? get() = calleeExpression?.typeReference
}

class KtDelegatedSuperTypeEntry internal constructor(session: KtTreeSession, node: LightNode) :
    KtSuperTypeListEntry(session, node) {
    val delegateExpression: KtExpression? get() = childrenOfType<KtExpression>().lastOrNull()
}

// ---------------------------------------------------------------------------------------------------------
// Callables
// ---------------------------------------------------------------------------------------------------------

class KtNamedFunction internal constructor(session: KtTreeSession, node: LightNode) :
    KtCallableDeclaration(session, node), KtFunction {

    override val bodyExpression: KtExpression? get() = childrenOfType<KtExpression>().lastOrNull()

    override val bodyBlockExpression: KtBlockExpression? get() = bodyExpression as? KtBlockExpression

    /** A `fun f() = expr` function has no block body, and its return type is inferred from the expression. */
    override fun hasBlockBody(): Boolean = bodyBlockExpression != null

    /** An abstract or expect function has neither kind of body. */
    override fun hasBody(): Boolean = bodyExpression != null

    override val equalsToken: KtElement? get() = child(KtTokens.EQ)

    val funKeyword: KtElement? get() = child(KtTokens.FUN_MODIFIER)

    val isLocal: Boolean get() = getStrictParentOfType<KtBlockExpression>() != null
}

class KtProperty internal constructor(session: KtTreeSession, node: LightNode) :
    KtCallableDeclaration(session, node) {

    val isVar: Boolean get() = hasChild(KtTokens.VAR_KEYWORD)

    val isLocal: Boolean get() = getStrictParentOfType<KtBlockExpression>() != null

    val delegate: KtPropertyDelegate? get() = firstChildOfType()

    fun hasDelegate(): Boolean = delegate != null

    /** The `by f()` expression, which is where a delegate diagnostic has to point. */
    val delegateExpression: KtExpression? get() = delegate?.expression

    fun hasDelegateExpression(): Boolean = delegateExpression != null

    val delegateExpressionOrInitializer: KtExpression? get() = delegateExpression ?: initializer

    val valOrVarKeyword: KtElement get() = child(KtTokens.VAL_KEYWORD) ?: child(KtTokens.VAR_KEYWORD) ?: this

    /** The `= …` initializer, told from the accessors and the type by position. */
    val initializer: KtExpression?
        get() {
            val equals = session.tree.findChildByType(node, KtTokens.EQ) ?: return null
            val at = session.tree.getStartOffset(equals)
            return children.firstOrNull { it.textOffset > at } as? KtExpression
        }

    fun hasInitializer(): Boolean = initializer != null

    val accessors: List<KtPropertyAccessor> get() = childrenOfType()

    val getter: KtPropertyAccessor? get() = accessors.firstOrNull { it.isGetter }

    val setter: KtPropertyAccessor? get() = accessors.firstOrNull { !it.isGetter }
}

class KtPropertyDelegate internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node) {
    val expression: KtExpression? get() = firstChildOfType()
    val property: KtProperty? get() = parent as? KtProperty
}

class KtPropertyAccessor internal constructor(session: KtTreeSession, node: LightNode) :
    KtDeclaration(session, node), KtDeclarationWithBody {

    val isGetter: Boolean get() = hasChild(KtTokens.GET_KEYWORD)

    val isSetter: Boolean get() = !isGetter

    /** The accessor's own declared return type, as in `get(): Int = …`. */
    val typeReference: KtTypeReference? get() = firstChildOfType()

    /**
     * The property this accessor belongs to.
     *
     * Nullable where PSI declared it non-null: an accessor only ever appears under a property in well-formed
     * code, but the parser is error-tolerant and a caller reading half-typed text can hold one that is not.
     */
    val property: KtProperty? get() = parent as? KtProperty

    override val bodyExpression: KtExpression? get() = childrenOfType<KtExpression>().lastOrNull()

    override val bodyBlockExpression: KtBlockExpression? get() = bodyExpression as? KtBlockExpression

    override val equalsToken: KtElement? get() = child(KtTokens.EQ)

    /** The `get` or `set` keyword, which stands where a named declaration would put its name. */
    val namePlaceholder: KtElement
        get() = child(KtTokens.GET_KEYWORD) ?: child(KtTokens.SET_KEYWORD) ?: this

    /** An accessor declared without a body (`var x: Int get`) is a parse fragment, not a declaration. */
    override fun hasBlockBody(): Boolean = bodyBlockExpression != null

    override fun hasBody(): Boolean = bodyExpression != null

    override val valueParameters: List<KtParameter>
        get() = firstChildOfType<KtParameterList>()?.parameters.orEmpty()

    val returnTypeReference: KtTypeReference? get() = firstChildOfType()
}

class KtTypeAlias internal constructor(session: KtTreeSession, node: LightNode) :
    KtNamedDeclaration(session, node), KtTypeParameterListOwner {
    override val typeParameterList: KtTypeParameterList? get() = firstChildOfType()
    override val typeParameters: List<KtTypeParameter> get() = typeParameterList?.parameters.orEmpty()
    override val typeConstraintList: KtTypeConstraintList? get() = firstChildOfType()
    override val typeConstraints: List<KtTypeConstraint> get() = typeConstraintList?.constraints.orEmpty()
    val typeReference: KtTypeReference? get() = firstChildOfType()
}

class KtParameterList internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node) {
    val parameters: List<KtParameter> get() = childrenOfType()
}

class KtParameter internal constructor(session: KtTreeSession, node: LightNode) :
    KtNamedDeclaration(session, node) {

    val typeReference: KtTypeReference? get() = firstChildOfType()

    val defaultValue: KtExpression?
        get() {
            val equals = session.tree.findChildByType(node, KtTokens.EQ) ?: return null
            val at = session.tree.getStartOffset(equals)
            return children.firstOrNull { it.textOffset > at } as? KtExpression
        }

    fun hasDefaultValue(): Boolean = defaultValue != null

    val isVarArg: Boolean get() = hasModifier(KtTokens.VARARG_MODIFIER)

    /** A primary-constructor parameter that also declares a property. */
    fun hasValOrVar(): Boolean = hasChild(KtTokens.VAL_KEYWORD) || hasChild(KtTokens.VAR_KEYWORD)

    val valOrVarKeyword: KtElement? get() = child(KtTokens.VAL_KEYWORD) ?: child(KtTokens.VAR_KEYWORD)

    val isMutable: Boolean get() = hasChild(KtTokens.VAR_KEYWORD)

    /** `{ (a, b) -> }`: a lambda parameter that destructures instead of naming one thing. */
    val destructuringDeclaration: KtDestructuringDeclaration? get() = firstChildOfType()
}

class KtTypeParameterList internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node) {
    val parameters: List<KtTypeParameter> get() = childrenOfType()
}

class KtTypeParameter internal constructor(session: KtTreeSession, node: LightNode) :
    KtNamedDeclaration(session, node) {

    /** Declaration-site variance: `<out T>` / `<in T>`, or invariant when neither is written. */
    val variance: Variance
        get() = when {
            hasModifier(KtTokens.IN_MODIFIER) -> Variance.IN_VARIANCE
            hasModifier(KtTokens.OUT_MODIFIER) -> Variance.OUT_VARIANCE
            else -> Variance.INVARIANT
        }

    val extendsBound: KtTypeReference? get() = firstChildOfType()
    val isReified: Boolean get() = hasModifier(KtTokens.REIFIED_MODIFIER)
}

class KtTypeConstraintList internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node) {
    val constraints: List<KtTypeConstraint> get() = childrenOfType()
}

class KtTypeConstraint internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node) {
    val subjectTypeParameterName: KtNameReferenceExpression? get() = firstChildOfType()
    val boundTypeReference: KtTypeReference? get() = firstChildOfType()
}

class KtDestructuringDeclaration internal constructor(session: KtTreeSession, node: LightNode) :
    KtDeclaration(session, node) {
    val entries: List<KtDestructuringDeclarationEntry> get() = childrenOfType()

    /** The `= f()` of `val (a, b) = f()`, which is the only child that is not an entry. */
    val initializer: KtExpression? get() = childrenOfType<KtExpression>().lastOrNull()

    fun hasInitializer(): Boolean = initializer != null

    val isVar: Boolean get() = hasChild(KtTokens.VAR_KEYWORD)

    val valOrVarKeyword: KtElement? get() = child(KtTokens.VAL_KEYWORD) ?: child(KtTokens.VAR_KEYWORD)
}

class KtDestructuringDeclarationEntry internal constructor(session: KtTreeSession, node: LightNode) :
    KtNamedDeclaration(session, node) {
    val typeReference: KtTypeReference? get() = firstChildOfType()

    /** An entry is mutable only through the whole declaration: `var (a, b) = …` makes both entries vars. */
    val isVar: Boolean get() = (parent as? KtDestructuringDeclaration)?.isVar == true
}

// ---------------------------------------------------------------------------------------------------------
// Modifiers, annotations, types
// ---------------------------------------------------------------------------------------------------------

class KtModifierList internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node) {

    override fun hasModifier(token: SyntaxElementType): Boolean = hasChild(token)

    /** The element carrying [token], for a diagnostic that has to underline the modifier itself. */
    fun getModifier(token: SyntaxElementType): KtElement? = child(token)

    override val annotationEntries: List<KtAnnotationEntry> get() = childrenOfType()

    /**
     * Every modifier present, in source order.
     *
     * The parser collapses each modifier keyword into a node of its own token kind, so these are the element
     * types of the non-annotation children.
     */
    val modifiers: List<SyntaxElementType>
        get() = children.filter { it !is KtAnnotationEntry }.map { it.elementType }
}

class KtAnnotationEntry internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node) {

    val typeReference: KtTypeReference? get() = child(KtNodeTypes.CONSTRUCTOR_CALLEE)?.firstChildOfType()

    /** The annotation's short name, which is what most call sites actually want. */
    val shortName: Name?
        get() = typeReference?.text?.substringAfterLast('.')?.substringBefore('<')?.let { Name.identifier(it) }

    val valueArgumentList: KtValueArgumentList? get() = firstChildOfType()

    val valueArguments: List<KtValueArgument> get() = valueArgumentList?.arguments.orEmpty()

    /** `field`, `get`, `file` and so on, when the annotation names a use-site target. */
    val useSiteTarget: String? get() = child(KtNodeTypes.ANNOTATION_TARGET)?.text
}

class KtTypeReference internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node), KtAnnotated, KtModifierListOwner {
    /** The type itself, with the reference's own modifiers and annotations stripped. */
    val typeElement: KtTypeElement? get() = children.firstOrNull { it !is KtModifierList } as? KtTypeElement
}

class KtUserType internal constructor(session: KtTreeSession, node: LightNode) : KtElement(session, node), KtTypeElement {

    /** The qualifier: `a.b` in `a.b.C`, or null for a simple name. */
    val qualifier: KtUserType? get() = firstChildOfType()

    val referenceExpression: KtNameReferenceExpression? get() = firstChildOfType()

    val referencedName: String? get() = referenceExpression?.getReferencedName()

    val typeArgumentList: KtTypeArgumentList? get() = firstChildOfType()

    val typeArguments: List<KtTypeProjection> get() = typeArgumentList?.arguments.orEmpty()
}

class KtNullableType internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node), KtTypeElement {
    val innerType: KtTypeElement? get() = children.firstOrNull { it !is KtModifierList } as? KtTypeElement
}

class KtIntersectionType internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node), KtTypeElement

class KtDynamicType internal constructor(session: KtTreeSession, node: LightNode) : KtElement(session, node), KtTypeElement

class KtFunctionType internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node), KtTypeElement {
    val receiverTypeReference: KtTypeReference?
        get() = child(KtNodeTypes.FUNCTION_TYPE_RECEIVER)?.firstChildOfType()

    val parameterList: KtParameterList? get() = firstChildOfType()

    val parameters: List<KtParameter> get() = parameterList?.parameters.orEmpty()

    val returnTypeReference: KtTypeReference? get() = childrenOfType<KtTypeReference>().lastOrNull()
}

class KtTypeArgumentList internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node) {
    val arguments: List<KtTypeProjection> get() = childrenOfType()
}

class KtTypeProjection internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node), KtAnnotated, KtModifierListOwner {
    val typeReference: KtTypeReference? get() = firstChildOfType()
    val isStar: Boolean get() = hasChild(KtTokens.MUL)

    val projectionKind: KtProjectionKind
        get() = when {
            isStar -> KtProjectionKind.STAR
            hasModifier(KtTokens.IN_MODIFIER) -> KtProjectionKind.IN
            hasModifier(KtTokens.OUT_MODIFIER) -> KtProjectionKind.OUT
            else -> KtProjectionKind.NONE
        }
}
