package dev.ide.kotlin.syntax.psi

import dev.ide.kotlin.syntax.KtNodeTypes
import dev.ide.kotlin.syntax.lexer.KtModifierKeywordToken
import dev.ide.kotlin.syntax.lexer.KtTokens
import dev.ide.kotlin.syntax.tree.AstNode
import dev.ide.kotlin.syntax.tree.IElementType
import dev.ide.kotlin.syntax.tree.TextRange
import dev.ide.kotlin.syntax.tree.TokenSet

/**
 * The typed view of the tree, mirroring `org.jetbrains.kotlin.psi.KtElement` and its subclasses.
 *
 * This layer is thin on purpose, and so is the compiler's: `KtProperty` there references `ASTNode`,
 * `IElementType`, `findChildByType` and `KtNodeTypes`, and nothing else. The typed classes are an interface
 * over element types, not a second data structure, which is why porting them is mechanical once the tree and
 * the vocabulary match.
 *
 * Accessor names are the compiler's. `initializer`, `bodyExpression`, `valueParameters`,
 * `getReturnTypeReference` and the rest are what the editor backend's 18,000 lines already call, and the
 * whole point of mirroring is that those call sites do not have to change.
 */
open class KtElement(val node: AstNode) {

    val elementType: IElementType get() = node.elementType

    val text: String get() = node.text

    val textRange: TextRange get() = node.textRange

    val textOffset: Int get() = node.startOffset

    val textLength: Int get() = node.textLength

    val parent: KtElement? get() = node.treeParent?.toPsi()

    val children: List<KtElement> get() = node.children.filter { !it.isTrivia() }.map { it.toPsi() }

    val firstChild: KtElement? get() = children.firstOrNull()

    val lastChild: KtElement? get() = children.lastOrNull()

    val nextSibling: KtElement? get() = node.treeNext?.toPsi()

    val prevSibling: KtElement? get() = node.treePrev?.toPsi()

    /** The file this element belongs to, or null for a detached subtree. */
    val containingKtFile: KtFile?
        get() {
            var current: AstNode? = node
            while (current != null) {
                if (current.elementType === KtNodeTypes.KT_FILE) return current.toPsi() as? KtFile
                current = current.treeParent
            }
            return null
        }

    internal fun child(type: IElementType): KtElement? = node.findChildByType(type)?.toPsi()

    internal fun child(types: TokenSet): KtElement? = node.findChildByType(types)?.toPsi()

    internal fun childrenOf(type: IElementType): List<KtElement> =
        node.findChildrenByType(type).map { it.toPsi() }

    // Internal rather than protected: these are read across element types (a class reaches into its body,
    // an annotation into its callee), which protected visibility would forbid.
    internal inline fun <reified T : KtElement> firstChildOfType(): T? =
        children.firstOrNull { it is T } as T?

    internal inline fun <reified T : KtElement> childrenOfType(): List<T> =
        children.filterIsInstance<T>()

    /** Does this element carry [token] in its modifier list? */
    open fun hasModifier(token: KtModifierKeywordToken): Boolean = modifierList?.hasModifier(token) == true

    open val modifierList: KtModifierList? get() = firstChildOfType()

    /** Annotation entries on this element, `@file:` ones included where they apply. */
    open val annotationEntries: List<KtAnnotationEntry> get() = modifierList?.annotationEntries.orEmpty()

    /**
     * The doc comment immediately preceding this element, if any.
     *
     * Whitespace is skipped, and so are ZERO-WIDTH siblings: a file always carries a package directive and an
     * import list even when it has neither, and those empty elements sit between the comment and the first
     * declaration.
     */
    val docComment: String?
        get() {
            var previous = node.treePrev
            while (previous != null &&
                (previous.elementType === dev.ide.kotlin.syntax.tree.TokenType.WHITE_SPACE ||
                    previous.textLength == 0)
            ) {
                previous = previous.treePrev
            }
            return if (previous?.elementType === KtTokens.DOC_COMMENT) previous.text else null
        }

    override fun toString(): String = "${elementType.debugName}${textRange}"

    override fun equals(other: Any?): Boolean = other is KtElement && other.node === node

    override fun hashCode(): Int = node.hashCode()
}

/** An element with a name, mirroring `KtNamedDeclaration`. */
abstract class KtNamedDeclaration(node: AstNode) : KtDeclaration(node) {

    /** The identifier token, or null when the name is missing (half-typed code, or an anonymous object). */
    open val nameIdentifier: AstNode?
        get() = node.children.firstOrNull { it.elementType === KtTokens.IDENTIFIER }

    open val name: String? get() = nameIdentifier?.text?.removeSurrounding("`")
}

/** Anything that declares something, mirroring `KtDeclaration`. */
abstract class KtDeclaration(node: AstNode) : KtExpression(node)

/** A declaration that can have a receiver, type parameters and value parameters. */
abstract class KtCallableDeclaration(node: AstNode) : KtNamedDeclaration(node) {

    val typeParameterList: KtTypeParameterList? get() = firstChildOfType()

    val typeParameters: List<KtTypeParameter> get() = typeParameterList?.parameters.orEmpty()

    val valueParameterList: KtParameterList? get() = firstChildOfType()

    val valueParameters: List<KtParameter> get() = valueParameterList?.parameters.orEmpty()

    /**
     * The extension receiver, or null.
     *
     * A callable has at most two type references directly under it — the receiver and the return type — and
     * the receiver is the one that comes BEFORE the name. Position is what separates them, not shape.
     */
    val receiverTypeReference: KtTypeReference?
        get() {
            val nameOffset = nameIdentifier?.startOffset ?: return null
            return childrenOfType<KtTypeReference>().firstOrNull { it.textOffset < nameOffset }
        }

    /** The declared return type, or null when it is inferred. */
    open val typeReference: KtTypeReference?
        get() {
            val nameOffset = nameIdentifier?.startOffset ?: return childrenOfType<KtTypeReference>().firstOrNull()
            return childrenOfType<KtTypeReference>().firstOrNull { it.textOffset > nameOffset }
        }

    val typeConstraintList: KtTypeConstraintList? get() = firstChildOfType()
}

// ---------------------------------------------------------------------------------------------------------
// File structure
// ---------------------------------------------------------------------------------------------------------

/** A parsed Kotlin file, mirroring `KtFile`. */
class KtFile(node: AstNode) : KtElement(node) {

    val packageDirective: KtPackageDirective? get() = firstChildOfType()

    /** The declared package, or the empty string for the default package. */
    val packageFqName: String get() = packageDirective?.qualifiedName ?: ""

    val importList: KtImportList? get() = firstChildOfType()

    val importDirectives: List<KtImportDirective> get() = importList?.imports.orEmpty()

    /** Top-level declarations, in source order. */
    val declarations: List<KtDeclaration> get() = childrenOfType()

    val fileAnnotationList: KtElement? get() = child(KtNodeTypes.FILE_ANNOTATION_LIST)
}

class KtPackageDirective(node: AstNode) : KtElement(node) {
    /** `a.b.c`, or the empty string when the directive is absent. */
    val qualifiedName: String
        get() = children.firstOrNull { it is KtExpression }?.text?.replace(" ", "") ?: ""
}

class KtImportList(node: AstNode) : KtElement(node) {
    val imports: List<KtImportDirective> get() = childrenOfType()
}

class KtImportDirective(node: AstNode) : KtElement(node) {

    /** `a.b.C` from `import a.b.C as D`, without the star. */
    val importedFqName: String?
        get() = children.firstOrNull { it is KtExpression }?.text?.replace(" ", "")

    val isAllUnder: Boolean get() = node.children.any { it.elementType === KtTokens.MUL }

    val alias: KtImportAlias? get() = firstChildOfType()

    /** The name this import introduces: the alias if there is one, otherwise the last name part. */
    val aliasName: String? get() = alias?.name ?: importedFqName?.substringAfterLast('.')
}

class KtImportAlias(node: AstNode) : KtElement(node) {
    val name: String? get() = node.findChildByType(KtTokens.IDENTIFIER)?.text
}

// ---------------------------------------------------------------------------------------------------------
// Classifiers
// ---------------------------------------------------------------------------------------------------------

/** A class, interface or object, mirroring `KtClassOrObject`. */
abstract class KtClassOrObject(node: AstNode) : KtNamedDeclaration(node) {

    val body: KtClassBody? get() = firstChildOfType()

    /** Members declared in the body. An enum's entries are NOT included; they are [KtClass.enumEntries]. */
    val declarations: List<KtDeclaration> get() = body?.declarations.orEmpty()

    val primaryConstructor: KtPrimaryConstructor? get() = firstChildOfType()

    val secondaryConstructors: List<KtSecondaryConstructor> get() = body?.childrenOfType<KtSecondaryConstructor>().orEmpty()

    val superTypeList: KtSuperTypeList? get() = firstChildOfType()

    val superTypeListEntries: List<KtSuperTypeListEntry> get() = superTypeList?.entries.orEmpty()

    val typeParameterList: KtTypeParameterList? get() = firstChildOfType()

    val typeParameters: List<KtTypeParameter> get() = typeParameterList?.parameters.orEmpty()
}

class KtClass(node: AstNode) : KtClassOrObject(node) {

    val isInterface: Boolean get() = node.children.any { it.elementType === KtTokens.INTERFACE_KEYWORD }

    val isEnum: Boolean get() = hasModifier(KtTokens.ENUM_KEYWORD)

    val isAnnotation: Boolean get() = hasModifier(KtTokens.ANNOTATION_KEYWORD)

    val isData: Boolean get() = hasModifier(KtTokens.DATA_KEYWORD)

    val isSealed: Boolean get() = hasModifier(KtTokens.SEALED_KEYWORD)

    val isInner: Boolean get() = hasModifier(KtTokens.INNER_KEYWORD)

    val enumEntries: List<KtEnumEntry> get() = body?.childrenOfType<KtEnumEntry>().orEmpty()
}

class KtObjectDeclaration(node: AstNode) : KtClassOrObject(node) {
    /** A companion object has the `companion` modifier and usually no name. */
    val isCompanion: Boolean get() = hasModifier(KtTokens.COMPANION_KEYWORD)
}

class KtClassBody(node: AstNode) : KtElement(node) {
    val declarations: List<KtDeclaration> get() = childrenOfType<KtDeclaration>().filter { it !is KtEnumEntry }
}

class KtEnumEntry(node: AstNode) : KtNamedDeclaration(node) {
    val initializerList: KtValueArgumentList? get() = firstChildOfType()
    val body: KtClassBody? get() = firstChildOfType()
}

class KtPrimaryConstructor(node: AstNode) : KtElement(node) {
    val valueParameterList: KtParameterList? get() = firstChildOfType()
    val valueParameters: List<KtParameter> get() = valueParameterList?.parameters.orEmpty()
}

class KtSecondaryConstructor(node: AstNode) : KtDeclaration(node) {
    val valueParameterList: KtParameterList? get() = firstChildOfType()
    val valueParameters: List<KtParameter> get() = valueParameterList?.parameters.orEmpty()
    val bodyExpression: KtBlockExpression? get() = firstChildOfType()
}

class KtClassInitializer(node: AstNode) : KtDeclaration(node) {
    val body: KtBlockExpression? get() = firstChildOfType()
}

class KtSuperTypeList(node: AstNode) : KtElement(node) {
    val entries: List<KtSuperTypeListEntry> get() = childrenOfType()
}

abstract class KtSuperTypeListEntry(node: AstNode) : KtElement(node) {
    val typeReference: KtTypeReference? get() = firstChildOfType()
}

class KtSuperTypeEntry(node: AstNode) : KtSuperTypeListEntry(node)

class KtSuperTypeCallEntry(node: AstNode) : KtSuperTypeListEntry(node) {
    val valueArgumentList: KtValueArgumentList? get() = firstChildOfType()
}

class KtDelegatedSuperTypeEntry(node: AstNode) : KtSuperTypeListEntry(node) {
    val delegateExpression: KtExpression? get() = childrenOfType<KtExpression>().lastOrNull()
}

// ---------------------------------------------------------------------------------------------------------
// Callables
// ---------------------------------------------------------------------------------------------------------

class KtNamedFunction(node: AstNode) : KtCallableDeclaration(node) {

    val bodyExpression: KtExpression?
        get() = childrenOfType<KtExpression>().lastOrNull()

    val bodyBlockExpression: KtBlockExpression? get() = bodyExpression as? KtBlockExpression

    /** A `fun f() = expr` function, whose return type is inferred from the body. */
    val hasBlockBody: Boolean get() = bodyBlockExpression != null

    val isLocal: Boolean get() = getStrictParentOfType<KtBlockExpression>() != null
}

class KtProperty(node: AstNode) : KtCallableDeclaration(node) {

    val isVar: Boolean get() = node.children.any { it.elementType === KtTokens.VAR_KEYWORD }

    val isLocal: Boolean get() = getStrictParentOfType<KtBlockExpression>() != null

    val delegate: KtPropertyDelegate? get() = firstChildOfType()

    val hasDelegate: Boolean get() = delegate != null

    /** The `= …` initializer, distinguished from the accessors and the type by position and shape. */
    val initializer: KtExpression?
        get() {
            val equals = node.children.firstOrNull { it.elementType === KtTokens.EQ } ?: return null
            return node.children
                .firstOrNull { it.startOffset > equals.startOffset && !it.isTrivia() }
                ?.toPsi() as? KtExpression
        }

    val accessors: List<KtPropertyAccessor> get() = childrenOfType()

    val getter: KtPropertyAccessor? get() = accessors.firstOrNull { it.isGetter }

    val setter: KtPropertyAccessor? get() = accessors.firstOrNull { !it.isGetter }
}

class KtPropertyDelegate(node: AstNode) : KtElement(node) {
    val expression: KtExpression? get() = firstChildOfType()
}

class KtPropertyAccessor(node: AstNode) : KtDeclaration(node) {

    val isGetter: Boolean get() = node.children.any { it.elementType === KtTokens.GET_KEYWORD }

    val isSetter: Boolean get() = !isGetter

    val bodyExpression: KtExpression?
        get() = childrenOfType<KtExpression>().lastOrNull()

    val valueParameters: List<KtParameter> get() = firstChildOfType<KtParameterList>()?.parameters.orEmpty()

    val returnTypeReference: KtTypeReference? get() = firstChildOfType()
}

class KtTypeAlias(node: AstNode) : KtNamedDeclaration(node) {
    val typeParameterList: KtTypeParameterList? get() = firstChildOfType()
    val typeReference: KtTypeReference? get() = firstChildOfType()
}

class KtParameterList(node: AstNode) : KtElement(node) {
    val parameters: List<KtParameter> get() = childrenOfType()
}

class KtParameter(node: AstNode) : KtNamedDeclaration(node) {

    val typeReference: KtTypeReference? get() = firstChildOfType()

    val defaultValue: KtExpression?
        get() {
            val equals = node.children.firstOrNull { it.elementType === KtTokens.EQ } ?: return null
            return node.children
                .firstOrNull { it.startOffset > equals.startOffset && !it.isTrivia() }
                ?.toPsi() as? KtExpression
        }

    val hasDefaultValue: Boolean get() = defaultValue != null

    val isVarArg: Boolean get() = hasModifier(KtTokens.VARARG_KEYWORD)

    /** A primary-constructor parameter that also declares a property. */
    val hasValOrVar: Boolean
        get() = node.children.any {
            it.elementType === KtTokens.VAL_KEYWORD || it.elementType === KtTokens.VAR_KEYWORD
        }

    val isMutable: Boolean get() = node.children.any { it.elementType === KtTokens.VAR_KEYWORD }
}

class KtTypeParameterList(node: AstNode) : KtElement(node) {
    val parameters: List<KtTypeParameter> get() = childrenOfType()
}

class KtTypeParameter(node: AstNode) : KtNamedDeclaration(node) {
    val extendsBound: KtTypeReference? get() = firstChildOfType()
    val isReified: Boolean get() = hasModifier(KtTokens.REIFIED_KEYWORD)
}

class KtTypeConstraintList(node: AstNode) : KtElement(node) {
    val constraints: List<KtTypeConstraint> get() = childrenOfType()
}

class KtTypeConstraint(node: AstNode) : KtElement(node) {
    val subjectTypeParameterName: KtNameReferenceExpression? get() = firstChildOfType()
    val boundTypeReference: KtTypeReference? get() = firstChildOfType()
}

class KtDestructuringDeclaration(node: AstNode) : KtDeclaration(node) {
    val entries: List<KtDestructuringDeclarationEntry> get() = childrenOfType()
}

class KtDestructuringDeclarationEntry(node: AstNode) : KtNamedDeclaration(node) {
    val typeReference: KtTypeReference? get() = firstChildOfType()
}

// ---------------------------------------------------------------------------------------------------------
// Modifiers, annotations, types
// ---------------------------------------------------------------------------------------------------------

class KtModifierList(node: AstNode) : KtElement(node) {

    override fun hasModifier(token: KtModifierKeywordToken): Boolean =
        node.children.any { it.elementType === token }

    override val annotationEntries: List<KtAnnotationEntry> get() = childrenOfType()

    /** Every modifier keyword present, in source order. */
    val modifiers: List<KtModifierKeywordToken>
        get() = node.children.mapNotNull { it.elementType as? KtModifierKeywordToken }
}

class KtAnnotationEntry(node: AstNode) : KtElement(node) {

    val typeReference: KtTypeReference?
        get() = child(KtNodeTypes.CONSTRUCTOR_CALLEE)?.firstChildOfType<KtTypeReference>()

    /** The annotation's short name, which is what most call sites actually want. */
    val shortName: String? get() = typeReference?.text?.substringAfterLast('.')?.substringBefore('<')

    val valueArgumentList: KtValueArgumentList? get() = firstChildOfType()

    val valueArguments: List<KtValueArgument> get() = valueArgumentList?.arguments.orEmpty()

    /** `field`, `get`, `file` and so on, when the annotation names a use-site target. */
    val useSiteTarget: String? get() = child(KtNodeTypes.ANNOTATION_TARGET)?.text
}

class KtTypeReference(node: AstNode) : KtElement(node) {
    /** The type itself, with the reference's own modifiers and annotations stripped. */
    val typeElement: KtElement? get() = children.firstOrNull { it !is KtModifierList }
}

class KtUserType(node: AstNode) : KtElement(node) {

    /** The qualifier: `a.b` in `a.b.C`, or null for a simple name. */
    val qualifier: KtUserType? get() = firstChildOfType()

    val referenceExpression: KtNameReferenceExpression? get() = firstChildOfType()

    val referencedName: String? get() = referenceExpression?.getReferencedName()

    val typeArgumentList: KtTypeArgumentList? get() = firstChildOfType()

    val typeArguments: List<KtTypeProjection> get() = typeArgumentList?.arguments.orEmpty()
}

class KtNullableType(node: AstNode) : KtElement(node) {
    val innerType: KtElement? get() = children.firstOrNull { it !is KtModifierList }
}

class KtIntersectionType(node: AstNode) : KtElement(node)

class KtDynamicType(node: AstNode) : KtElement(node)

class KtFunctionType(node: AstNode) : KtElement(node) {
    val receiverTypeReference: KtTypeReference?
        get() = child(KtNodeTypes.FUNCTION_TYPE_RECEIVER)?.firstChildOfType<KtTypeReference>()

    val parameterList: KtParameterList? get() = firstChildOfType()

    val parameters: List<KtParameter> get() = parameterList?.parameters.orEmpty()

    val returnTypeReference: KtTypeReference? get() = childrenOfType<KtTypeReference>().lastOrNull()
}

class KtTypeArgumentList(node: AstNode) : KtElement(node) {
    val arguments: List<KtTypeProjection> get() = childrenOfType()
}

class KtTypeProjection(node: AstNode) : KtElement(node) {
    val typeReference: KtTypeReference? get() = firstChildOfType()
    val isStar: Boolean get() = node.children.any { it.elementType === KtTokens.MUL }
}
