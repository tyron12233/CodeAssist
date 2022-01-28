package com.tyron.kotlin_completion.completion

import android.util.Log
import com.tyron.completion.model.CompletionItem
import com.tyron.completion.model.CompletionList
import com.tyron.completion.model.DrawableKind
import com.tyron.completion.model.Position
import com.tyron.completion.progress.ProgressManager
import com.tyron.kotlin_completion.CompiledFile
import com.tyron.kotlin_completion.index.Symbol
import com.tyron.kotlin_completion.index.SymbolIndex
import com.tyron.kotlin_completion.util.PsiUtils
import com.tyron.kotlin_completion.util.containsCharactersInOrder
import com.tyron.kotlin_completion.util.stringDistance
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.lexer.KtKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.java.descriptors.JavaMethodDescriptor
import org.jetbrains.kotlin.load.java.sources.JavaSourceElement
import org.jetbrains.kotlin.load.java.structure.JavaMethod
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.HierarchicalScope
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.checker.KotlinTypeChecker
import java.time.Duration
import java.time.Instant

inline fun <reified Find> PsiElement.findParent() =
    PsiUtils.getParentsWithSelf(this).filterIsInstance<Find>().firstOrNull()

const val MIN_SORT_LENGTH = 3
const val MAX_COMPLETION_ITEMS = 50

fun completions(
    file: CompiledFile,
    cursor: Int,
    index: SymbolIndex,
    partial: String
): CompletionList {

    val (elementItems, isExhaustive, receiver) = elementCompletionItems(file, cursor, partial)

    val elementItemList = elementItems.toList()
    val elementItemLabels = elementItemList.mapNotNull { it.label }.toSet()
//
    val items = (elementItemList.asSequence()
            + (if (!isExhaustive) indexCompletionItems(
        file,
        cursor,
        receiver,
        index,
        partial
    ).filter { it.label !in elementItemLabels } else emptySequence())
            + (if (elementItemList.isEmpty()) keywordCompletionItems(partial) else emptySequence())
            )

    val itemList = items
        .take(MAX_COMPLETION_ITEMS)
        .toList()
        .onEachIndexed { i, item -> item.data = i.toString().padStart(2, '0') }
    val isIncomplete = itemList.size >= MAX_COMPLETION_ITEMS || elementItemList.isEmpty()

    val list = CompletionList()
    list.items = itemList
    list.isIncomplete = isIncomplete
    return list
}

private fun indexCompletionItems(
    file: CompiledFile,
    cursor: Int,
    receiver: KtExpression?,
    index: SymbolIndex,
    partial: String
): Sequence<CompletionItem> {
    val start = Instant.now();
    val parsedFile = file.parse;
    val imports = parsedFile.importDirectives;

    val wildCardPackages = imports
        .mapNotNull { it.importPath }
        .filter { it.isAllUnder }
        .map { it.fqName }
        .toSet()

    val importNames = imports
        .mapNotNull { it.importedFqName?.shortName() }
        .toSet()
    val receiverType =
        receiver?.let { expr ->
            file.scopeAtPoint(cursor)?.let { file.typeOfExpression(expr, it) }
        }
    val receiverTypeName =
        if (receiverType?.constructor?.declarationDescriptor == null) null else
            PsiUtils.getFqNameSafe(receiverType.constructor.declarationDescriptor)

    val result = index
        .query(partial, receiverTypeName, limit = MAX_COMPLETION_ITEMS)
        .asSequence()
        .filter { it.kind != Symbol.Kind.MODULE }
        .filter { it.fqName.shortName() !in importNames && it.fqName.parent() !in wildCardPackages }
        .filter {
            it.visibility == Symbol.Visibility.PUBLIC
                    || it.visibility == Symbol.Visibility.PROTECTED
                    || it.visibility == Symbol.Visibility.INTERNAL
        }
        .map {
            CompletionItem().apply {
                label = it.fqName.shortName().toString()
                commitText = label
                cursorOffset = label.length
                iconKind = when (it.kind) {
                    Symbol.Kind.CLASS -> DrawableKind.Class
                    Symbol.Kind.INTERFACE -> DrawableKind.Interface
                    Symbol.Kind.FUNCTION -> DrawableKind.Method
                    Symbol.Kind.VARIABLE -> DrawableKind.LocalVariable
                    Symbol.Kind.FIELD -> DrawableKind.Field
                    else -> DrawableKind.Method
                }
                detail = "(import from ${it.fqName.parent()})"
//                val pos = findImportInsertionPosition(parsedFile, it.fqName)
//                val prefix = if (importNames.isEmpty()) "\n\n" else "\n"
//                additionalTextEdits =
//                    listOf(TextEdit(Range(pos, pos), "${prefix}import ${it.fqName}"))
            }
        }

    Log.d(
        "IndexCompletions",
        "IndexCompletions took " + Duration.between(start, Instant.now()).toMillis() + " ms"
    )

    return result;
}

private fun findImportInsertionPosition(parsedFile: KtFile, fqName: FqName): Position =
    (closestImport(parsedFile.importDirectives, fqName) as? KtElement
        ?: parsedFile.packageDirective as? KtElement)
        ?.let(com.tyron.kotlin_completion.position.Position::location)
        ?.range
        ?.end
        ?: Position(0, 0)


private fun closestImport(imports: List<KtImportDirective>, fqName: FqName): KtImportDirective? =
    imports
        .asReversed()
        .maxByOrNull { it.importedFqName?.let { matchingPrefixLength(it, fqName) } ?: 0 }

private fun matchingPrefixLength(left: FqName, right: FqName): Int =
    left.pathSegments().asSequence().zip(right.pathSegments().asSequence())
        .takeWhile { it.first == it.second }
        .count()

/** Finds keyword completions starting with the given partial identifier. */
private fun keywordCompletionItems(partial: String): Sequence<CompletionItem> {
    return (KtTokens.SOFT_KEYWORDS.types + KtTokens.KEYWORDS.types).asSequence()
        .mapNotNull { (it as? KtKeywordToken)?.value }
        .filter { it.startsWith(partial) }
        .map {
            CompletionItem().apply {
                label = it
                iconKind = DrawableKind.Keyword
                commitText = label
                cursorOffset = label.length
            }
        }
}

fun functionInsertText(desc: FunctionDescriptor, snippetsEnabled: Boolean, name: String): String {
    val parameters = desc.valueParameters
    val hasTrailingLambda = RenderCompletionItem.isFunctionType(parameters.lastOrNull()?.type)

    return if (snippetsEnabled) {
        if (hasTrailingLambda) {
            val parenthesizedParams =
                parameters.dropLast(1).ifEmpty { null }?.let { "(${valueParametersSnippet(it)})" }
                    ?: ""
            "$name$parenthesizedParams { \${${parameters.size}:${parameters.last().name}} }"
        } else {
            "$name(${valueParametersSnippet(parameters)})"
        }
    } else {
        if (hasTrailingLambda) {
            "$name { }"
        } else {
            "$name()"
        }
    }
}


private fun valueParametersSnippet(parameters: List<ValueParameterDescriptor>) = ProgressManager.checkCanceled().apply {
    parameters
        .asSequence()
        .filterNot { it.declaresDefaultValue() }
        .mapIndexed { index, vpd -> "\${${index + 1}:${vpd.name}}" }
        .joinToString()
}

private fun elementCompletionItems(
    file: CompiledFile,
    cursor: Int,
    partial: String
): ElementCompletionItems {
    ProgressManager.checkCanceled()


    val surroundingElement = completableElement(file, cursor) ?: return ElementCompletionItems(
        emptySequence(),
        true,
        null
    )

    val completions = elementCompletions(file, cursor, surroundingElement)

    val matchesName = completions.filter {
        containsCharactersInOrder(
            name(it),
            partial,
            caseSensitive = false
        )
    }
    val sorted = matchesName.takeIf { partial.length >= MIN_SORT_LENGTH }
        ?.sortedBy { stringDistance(name(it), partial) }
        ?: matchesName.sortedBy { if (name(it).startsWith(partial)) 0 else 1 }
    val visible = sorted.filter(isVisible(file, cursor))

    val isExhaustive = surroundingElement !is KtNameReferenceExpression
            && surroundingElement !is KtTypeElement
            && surroundingElement !is KtQualifiedExpression
    val receiver = (surroundingElement as? KtQualifiedExpression)?.receiverExpression

    return ElementCompletionItems(
        visible.map { completionItem(it, surroundingElement, file) },
        isExhaustive,
        receiver
    )
}

private val callPattern = Regex("(.*)\\((?:\\$\\d+)?\\)(?:\\$0)?")
private val methodSignature =
    Regex("""(?:fun|constructor) (?:<(?:[a-zA-Z?f\!\: ]+)(?:, [A-Z])*> )?([a-zA-Z]+\(.*\))""")

private fun completionItem(
    d: DeclarationDescriptor,
    surroundingElement: KtElement,
    file: CompiledFile
): CompletionItem {
    ProgressManager.checkCanceled();
    val renderWithSnippets = false
    //surroundingElement !is KtCallableReferenceExpression
    //            && surroundingElement !is KtImportDirective
    val result = d.accept(RenderCompletionItem(renderWithSnippets), null)

    result.label = methodSignature.find(result.detail)?.groupValues?.get(1) ?: result.label

    if (isNotStaticJavaMethod(d) && (isGetter(d) || isSetter(d))) {
        val name = extractPropertyName(d)

        result.detail += " (from ${result.label})"
        result.label = name
        result.commitText = name
        //  result.filterText = name
    }

    if (KotlinBuiltIns.isDeprecated(d)) {
        // result.tags = listOf(CompletionItemTag.Deprecated)
    }

    val matchCall = callPattern.matchEntire(result.commitText)

    if (file.lineAfter(PsiUtils.getEndOffset(surroundingElement))
            .startsWith("(") && matchCall != null
    ) {
        result.commitText = matchCall.groups[1]!!.value
    }

    result.cursorOffset = result.commitText.length;

    return result
}

private fun extractPropertyName(d: DeclarationDescriptor): String {
    val match = Regex("(get|set)?((?:(?:is)|[A-Z])\\w*)").matchEntire(d.name.identifier)!!
    val upper = match.groups[2]!!.value

    return upper[0].lowercaseChar() + upper.substring(1)
}

private fun isGetter(d: DeclarationDescriptor): Boolean =
    d is CallableDescriptor &&
            !d.name.isSpecial &&
            d.name.identifier.matches(Regex("(get|is)[A-Z]\\w+")) &&
            d.valueParameters.isEmpty()

private fun isSetter(d: DeclarationDescriptor): Boolean =
    d is CallableDescriptor &&
            !d.name.isSpecial &&
            d.name.identifier.matches(Regex("set[A-Z]\\w+")) &&
            d.valueParameters.size == 1

fun isNotStaticJavaMethod(
    descriptor: DeclarationDescriptor
): Boolean {
    val javaMethodDescriptor = descriptor as? JavaMethodDescriptor ?: return true
    val source = javaMethodDescriptor.source as? JavaSourceElement ?: return true
    val javaElement = source.javaElement
    return javaElement is JavaMethod && !javaElement.isStatic
}

fun name(d: DeclarationDescriptor): String {
    if (d is ConstructorDescriptor)
        return d.constructedClass.name.identifier
    else
        return d.name.identifier
}

fun isVisible(file: CompiledFile, cursor: Int): (DeclarationDescriptor) -> Boolean {
    val el = file.elementAtPoint(cursor) ?: return { true }
    val from = PsiUtils.getParentsWithSelf(el)
        .mapNotNull { file.compile[BindingContext.DECLARATION_TO_DESCRIPTOR, it] }
        .firstOrNull() ?: return { true }

    fun check(target: DeclarationDescriptor): Boolean {
        val visible = isDeclarationVisible(target, from)

        // if (!visible) logHidden(target, from)

        return visible
    }

    return ::check
}

// We can't use the implementations in Visibilities because they don't work with our type of incremental compilation
// Instead, we implement our own "liberal" visibility checker that defaults to visible when in doubt
private fun isDeclarationVisible(
    target: DeclarationDescriptor,
    from: DeclarationDescriptor
): Boolean =
    PsiUtils.getParentsWithSelf(target)
        .filterIsInstance<DeclarationDescriptorWithVisibility>()
        .none { isNotVisible(it, from) }

fun isNotVisible(
    target: DeclarationDescriptorWithVisibility,
    from: DeclarationDescriptor
): Boolean {
    return when (target.visibility.delegate) {
        Visibilities.Private, Visibilities.PrivateToThis -> {
            if (DescriptorUtils.isTopLevelDeclaration(target))
                !sameFile(target, from)
            else
                !sameParent(target, from)
        }
        Visibilities.Protected -> {
            !subclassParent(target, from)
        }
        else -> false
    }
}

fun sameFile(target: DeclarationDescriptor, from: DeclarationDescriptor): Boolean {
    val targetFile = DescriptorUtils.getContainingSourceFile(target)
    val fromFile = DescriptorUtils.getContainingSourceFile(from)

    return if (targetFile == SourceFile.NO_SOURCE_FILE || fromFile == SourceFile.NO_SOURCE_FILE) true
    else targetFile.name == fromFile.name
}

fun sameParent(target: DeclarationDescriptor, from: DeclarationDescriptor): Boolean {
    val targetParent =
        PsiUtils.getParentsWithSelf(target).mapNotNull(::isParentClass).firstOrNull() ?: return true
    val fromParents = PsiUtils.getParentsWithSelf(from).mapNotNull(::isParentClass).toList()

    return fromParents.any { PsiUtils.getFqNameSafe(it) == PsiUtils.getFqNameSafe(targetParent) }
}

fun subclassParent(target: DeclarationDescriptor, from: DeclarationDescriptor): Boolean {
    val targetParent =
        PsiUtils.getParentsWithSelf(target).mapNotNull(::isParentClass).firstOrNull() ?: return true
    val fromParents = PsiUtils.getParentsWithSelf(from).mapNotNull(::isParentClass).toList()

    if (fromParents.isEmpty()) return true
    else return fromParents.any { DescriptorUtils.isSubclass(it, targetParent) }
}

private fun isExtensionFor(type: KotlinType, extensionFunction: CallableDescriptor): Boolean {
    val receiverType =
        PsiUtils.replaceArgumentsWithStarProjections(extensionFunction.extensionReceiverParameter?.type)
            ?: return false
    return KotlinTypeChecker.DEFAULT.isSubtypeOf(type, receiverType)
            || (TypeUtils.getTypeParameterDescriptorOrNull(receiverType)
        ?.isGenericExtensionFor(type) ?: false)
}

private fun TypeParameterDescriptor.isGenericExtensionFor(type: KotlinType): Boolean =
    upperBounds.all { KotlinTypeChecker.DEFAULT.isSubtypeOf(type, it) }

private fun isParentClass(declaration: DeclarationDescriptor): ClassDescriptor? =
    if (declaration is ClassDescriptor && !DescriptorUtils.isCompanionObject(declaration))
        declaration
    else null


fun completableElement(file: CompiledFile, cursor: Int): KtElement? {
    ProgressManager.checkCanceled()
    val el = file.parseAtPoint(cursor - 1, false) ?: return null
    // import x.y.?
    return el.findParent<KtImportDirective>()
    // package x.y.?
        ?: el.findParent<KtPackageDirective>()
        // :?
        ?: el.parent as? KtTypeElement
        // .?
        ?: el as? KtQualifiedExpression
        ?: el.parent as? KtQualifiedExpression
        // something::?
        ?: el as? KtCallableReferenceExpression
        ?: el.parent as? KtCallableReferenceExpression
        // something.foo() with cursor in the method
        ?: el.parent?.parent as? KtQualifiedExpression
        // ?
        ?: el as? KtNameReferenceExpression
}

fun elementCompletions(
    file: CompiledFile,
    cursor: Int,
    surroundingElement: KtElement
): Sequence<DeclarationDescriptor> {
    ProgressManager.checkCanceled()
    return when (surroundingElement) {
        // import x.y.?
        is KtImportDirective -> {
            Log.d("ElementCompletions", "Completing import: " + surroundingElement.text)
            val module =
                file.container.resolve(ModuleDescriptor::class.java)?.getValue() as ModuleDescriptor
            val match = Regex("import ((\\w+\\.)*)[\\w*]*").matchEntire(surroundingElement.text)
                ?: return emptySequence()
            val parentDot = if (match.groupValues[1].isNotBlank()) match.groupValues[1] else "."
            val parent = parentDot.substring(0, parentDot.length - 1)
            //LOG.debug("Looking for members of package '{}'", parent)
            val parentPackage = module.getPackage(FqName.fromSegments(parent.split('.')))
            parentPackage.memberScope.getContributedDescriptors().asSequence()
        }
        // package x.y.?
        is KtPackageDirective -> {
            Log.d("ElementCompletions", "Completing package directive " + surroundingElement.text)
            val module =
                file.container.resolve(ModuleDescriptor::class.java)?.getValue() as ModuleDescriptor
            val match = Regex("package ((\\w+\\.)*)[\\w*]*").matchEntire(surroundingElement.text)
                ?: return emptySequence()
            val parentDot = if (match.groupValues[1].isNotBlank()) match.groupValues[1] else "."
            val parent = parentDot.substring(0, parentDot.length - 1)
            Log.d("ElementCompletions", "Looking for members of $parent")
            val parentPackage = module.getPackage(FqName.fromSegments(parent.split('.')))
            parentPackage.memberScope.getContributedDescriptors(DescriptorKindFilter.PACKAGES)
                .asSequence()

        }
        // :?
        is KtTypeElement -> {
            // : Outer.?
            if (surroundingElement is KtUserType && surroundingElement.qualifier != null) {
                val referenceTarget =
                    file.referenceAtPoint(PsiUtils.getStartOffset(surroundingElement.qualifier!!))?.second
                return if (referenceTarget is ClassDescriptor) {
                    Log.d(
                        "ElementCompletions",
                        "Completing members of " + PsiUtils.getFqNameSafe(referenceTarget)
                    )
                    referenceTarget.unsubstitutedInnerClassesScope.getContributedDescriptors()
                        .asSequence()
                } else {
                    //  LOG.warn("No type reference in '{}'", surroundingElement.text)
                    emptySequence()
                }
            } else {
                // : ?
                Log.d("ElementCompletions", "Completing type identifier " + surroundingElement.text)
                val scope = file.scopeAtPoint(cursor) ?: return emptySequence()
                scopeChainTypes(scope)
            }
            emptySequence()
        }
        // .?
        is KtQualifiedExpression -> {
//            LOG.info("Completing member expression '{}'", surroundingElement.text)
            completeMembers(
                file,
                cursor,
                surroundingElement.receiverExpression,
                surroundingElement is KtSafeQualifiedExpression
            )
        }
        is KtCallableReferenceExpression -> {
            // something::?
            if (surroundingElement.receiverExpression != null) {
                //LOG.info("Completing method reference '{}'", surroundingElement.text)
                completeMembers(file, cursor, surroundingElement.receiverExpression!!)
            }
            // ::?
            else {
                // LOG.info("Completing function reference '{}'", surroundingElement.text)
                val scope = file.scopeAtPoint(PsiUtils.getStartOffset(surroundingElement))
                    ?: return emptySequence()
                identifiers(scope)
            }
        }
        // ?
        is KtNameReferenceExpression -> {
            //LOG.info("Completing identifier '{}'", surroundingElement.text)
            val scope = file.scopeAtPoint(PsiUtils.getStartOffset(surroundingElement))
                ?: return emptySequence()
            identifiers(scope)
        }
        else -> {
            Log.d(
                "ElementCompletions",
                surroundingElement::class.simpleName + " " + surroundingElement.text + " didn't look like a type, a member, or an identifier"
            )
            emptySequence()
        }
    }
}

private fun completeMembers(
    file: CompiledFile,
    cursor: Int,
    receiverExpr: KtExpression,
    unwrapNullable: Boolean = false
): Sequence<DeclarationDescriptor> {
    ProgressManager.checkCanceled()
    // thingWithType.?
    var descriptors = emptySequence<DeclarationDescriptor>()
    file.scopeAtPoint(cursor)?.let { lexicalScope ->
        file.typeOfExpression(receiverExpr, lexicalScope)?.let { expressionType ->
            ProgressManager.checkCanceled()
            val receiverType = if (unwrapNullable) try {
                TypeUtils.makeNotNullable(expressionType)
            } catch (e: Exception) {
                //  LOG.printStackTrace(e)
                expressionType
            } else expressionType

            // LOG.debug("Completing members of instance '{}'", receiverType)
            val members = receiverType.memberScope.getContributedDescriptors().asSequence()
            val extensions =
                extensionFunctions(lexicalScope).filter {
                    ProgressManager.checkCanceled()
                    isExtensionFor(receiverType, it)
                }
            descriptors = members + extensions

            if (!isCompanionOfEnum(receiverType)) {
                return descriptors
            }
        }
    }

    // JavaClass.?
    val referenceTarget = file.referenceAtPoint(PsiUtils.getEndOffset(receiverExpr) - 1)?.second
    if (referenceTarget is ClassDescriptor) {
        // LOG.debug("Completing static members of '{}'", referenceTarget.fqNameSafe)
        val statics = referenceTarget.staticScope.getContributedDescriptors().asSequence()
        val classes =
            referenceTarget.unsubstitutedInnerClassesScope.getContributedDescriptors().asSequence()
        return descriptors + statics + classes
    }

    //LOG.debug("Can't find member scope for {}", receiverExpr.text)
    return emptySequence()
}

private fun isCompanionOfEnum(kotlinType: KotlinType): Boolean {
    val classDescriptor = TypeUtils.getClassDescriptor(kotlinType)
    val isCompanion = DescriptorUtils.isCompanionObject(classDescriptor)
    if (!isCompanion) {
        return false
    }
    return DescriptorUtils.isEnumClass(classDescriptor?.containingDeclaration)
}

fun scopeChainTypes(scope: LexicalScope): Sequence<DeclarationDescriptor> =
    PsiUtils.getParentsWithSelf(scope).flatMap(::scopeTypes)

private val TYPES_FILTER =
    DescriptorKindFilter(DescriptorKindFilter.NON_SINGLETON_CLASSIFIERS_MASK or DescriptorKindFilter.TYPE_ALIASES_MASK)

private fun scopeTypes(scope: HierarchicalScope): Sequence<DeclarationDescriptor> =
    scope.getContributedDescriptors(TYPES_FILTER).asSequence()

private fun identifiers(scope: LexicalScope): Sequence<DeclarationDescriptor> =
    PsiUtils.getParentsWithSelf(scope)
        .flatMap {
            ProgressManager.checkCanceled()
            scopeIdentifiers(it)
        }
        .flatMap(::explodeConstructors)

private fun explodeConstructors(declaration: DeclarationDescriptor): Sequence<DeclarationDescriptor> {
    return when (declaration) {
        is ClassDescriptor ->
            declaration.constructors.asSequence() + declaration
        else ->
            sequenceOf(declaration)
    }
}

private fun extensionFunctions(scope: LexicalScope): Sequence<CallableDescriptor> =
    PsiUtils.getParentsWithSelf(scope).flatMap(::scopeExtensionFunctions)

private fun scopeExtensionFunctions(scope: HierarchicalScope): Sequence<CallableDescriptor> =
    scope.getContributedDescriptors(DescriptorKindFilter.CALLABLES).asSequence()
        .filterIsInstance<CallableDescriptor>()
        .filter { DescriptorUtils.isExtension(it) }


private fun scopeIdentifiers(scope: HierarchicalScope): Sequence<DeclarationDescriptor> {
    ProgressManager.checkCanceled()
    val locals = scope.getContributedDescriptors().asSequence()
    val members = implicitMembers(scope)
    return locals + members
}

private fun implicitMembers(scope: HierarchicalScope): Sequence<DeclarationDescriptor> {
    ProgressManager.checkCanceled()
    if (scope !is LexicalScope) return emptySequence()
    val implicit = scope.implicitReceiver ?: return emptySequence()
    return implicit.type.memberScope.getContributedDescriptors().asSequence()
}



