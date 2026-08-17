package dev.ide.lang.kotlin.resolve

import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.TypeRef
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

/** Extension-callable resolution: member extensions in scope for a receiver type, receiver binding, and delegate-operator availability. */

/** Whether a `getValue`/`setValue` operator named [op] is available for a delegate of type [delegateType]:
 *  a plain member, or an in-scope extension. Returns true (don't flag) when none is modeled at all. */
internal fun KotlinResolver.delegateOperatorInScope(delegateType: KotlinType, op: String): Boolean {
    val candidates =
        service.membersNamed(delegateType.qualifiedName, delegateType.typeArguments, op)
            .filter { it.kind == SymbolKind.METHOD }
    if (candidates.isEmpty()) return true // operator not modeled on the classpath → conservative
    return candidates.any { !it.isExtension || extensionInScope(it) }
}

/** Whether the extension [sym] is in scope here — imported (explicit/star), same-package, or
 *  default-imported. No package info → don't guess a rejection. Mirrors `KotlinSourceAnalyzer.extensionInScope`. */
internal fun KotlinResolver.extensionInScope(sym: KotlinSymbol): Boolean {
    val pkg =
        sym.packageName ?: sym.declaringClassFqn?.substringBeforeLast('.', "")?.ifEmpty { null }
        ?: return true
    if (pkg == fileContext.packageName || dev.ide.lang.kotlin.symbols.DefaultImports.isDefaultImported(
            pkg
        )
    ) return true
    return fileContext.imports.any { imp -> if (imp.isStar) imp.packageName == pkg else imp.fqn == "$pkg.${sym.name}" }
}

/**
 * Member-extension callables in scope at [offset] applicable to a receiver of [receiverType] — an extension
 * declared INSIDE a class/object whose instance is an implicit `this` here, so it resolves like a member
 * WITHOUT an import. Two cases: `RowScope`'s `fun Modifier.weight()` applies to a `Modifier` while inside a
 * `Row { }` (the lambda's receiver scope), and a `fun Map<…>.printMap()` declared in the class you're editing
 * applies to a `Map` value inside that class. Sourced from BOTH the LIVE buffer's enclosing classes (a
 * just-typed extension, before the disk model catches up) and the symbol model's implicit-receiver types (a
 * saved / cross-file declaring class, or a `with(x){}` block receiver). Kept scope-gated for soundness, so
 * the extension never leaks onto a receiver outside its declaring scope. [namePrefix] empty = all.
 */
fun KotlinResolver.scopeMemberExtensions(
    offset: Int,
    receiverType: KotlinType,
    namePrefix: String = ""
): List<KotlinSymbol> {
    if (receiverType.isTypeParameter) return emptyList()
    val recvTargets =
        (listOf(receiverType.qualifiedName) + service.supertypesOf(receiverType.qualifiedName)
            .filterIsInstance<KotlinType>().map { it.qualifiedName }).toHashSet()

    fun matches(n: String) = namePrefix.isEmpty() || n.startsWith(namePrefix, ignoreCase = true)
    val out = ArrayList<KotlinSymbol>()
    val liveOwners = HashSet<String>()
    // A declared extension whose receiver matches → a bound symbol in [out]. Shared by the class-member and
    // local cases below, which differ only in where the declaration was found.
    fun collect(d: KtCallableDeclaration) {
        val recvRef = d.receiverTypeReference ?: return
        val name = d.name ?: return
        if (!matches(name)) return
        val recvFqn = service.resolveTypeName(recvRef.text, fileContext) ?: return
        if (recvFqn !in recvTargets) return
        sameFileMemberExtension(d, recvFqn)?.let { out += bindMemberExtensionReceiver(it, receiverType) }
    }
    // (a) Live-buffer enclosing classes/objects — covers an extension just typed in the file being edited
    //     (the disk-based symbol model may not carry it yet) — and enclosing BLOCKS, which carry LOCAL
    //     extensions (`fun String.twice()` declared in a function body). A local belongs to no class, so
    //     neither the class walk nor the disk model sees it, and `"a".twice()` used to read as unresolved.
    var node: PsiElement? = elementAt(offset)
    while (node != null) {
        if (node is KtClassOrObject) {
            node.fqName?.asString()?.let { liveOwners += it }
            for (d in node.declarations) if (d is KtCallableDeclaration) collect(d)
        }
        if (node is KtBlockExpression) forEachLocalFunction(node) {
            if (localFunctionVisibleAt(it, offset)) collect(it)
        }
        node = node.parent
    }
    // (b) Model implicit-receiver types (an extension-fn receiver, a `with`/`apply` block receiver, or a
    //     saved / cross-file enclosing class) — query their members for matching extensions.
    for (scope in implicitReceiversAt(offset)) {
        if (scope.qualifiedName in liveOwners) continue // already covered from the live buffer above
        service.membersForCompletion(scope.qualifiedName, scope.typeArguments, namePrefix)
            .filter { it.isExtension && it.receiverTypeFqn != null && it.receiverTypeFqn in recvTargets }
            .forEach { out += bindMemberExtensionReceiver(it, receiverType) }
    }
    // (c) IMPORTED through a singleton: `import okhttp3.MediaType.Companion.toMediaType`, then
    //     `"application/json".toMediaType()`. A member of an object/companion needs no dispatch receiver, so
    //     the import alone puts the extension in scope, and this is the one member-extension case an import
    //     does reach (a regular class's member extension can never be imported). Cases (a)/(b) are
    //     import-free by construction, which is why the seam had no import path at all and the whole OkHttp /
    //     Retrofit idiom read as an unresolved reference.
    out += importedSingletonExtensions(recvTargets, receiverType, namePrefix, liveOwners)
    return out
}

/**
 * Member extensions the file's imports bring into scope through an `object` or companion object, applicable to
 * a receiver in [recvTargets]. Both import spellings Kotlin accepts are handled: through the companion itself
 * (`import Foo.Companion.bar`, the OkHttp form) and through the enclosing class (`import Foo.bar`), plus a
 * star import of either.
 *
 * The container's last segment must start with an uppercase letter, which is what keeps this off the hot path:
 * an ordinary `import kotlinx.coroutines.flow.Flow` has a package as its container and is rejected before any
 * type lookup. A lowercase-named object would be missed, which is a naming-convention violation and strictly
 * better than paying a member query per import on every completion keystroke.
 */
private fun KotlinResolver.importedSingletonExtensions(
    recvTargets: Set<String>,
    receiverType: KotlinType,
    namePrefix: String,
    liveOwners: Set<String>,
): List<KotlinSymbol> {
    val out = ArrayList<KotlinSymbol>()
    for (imp in fileContext.imports) {
        val container = if (imp.isStar) imp.fqn else imp.fqn.substringBeforeLast('.', "")
        if (container.isEmpty() || container in liveOwners) continue
        if (container.substringAfterLast('.').firstOrNull()?.isUpperCase() != true) continue
        // A non-star import names ONE member, so a prefix that can't match it skips the lookup entirely.
        val member = if (imp.isStar) null else imp.fqn.substringAfterLast('.')
        if (member != null && namePrefix.isNotEmpty() && !member.startsWith(namePrefix, ignoreCase = true)) continue
        val candidates =
            if (service.isSingletonObject(container)) service.membersForCompletion(container, emptyList(), member ?: namePrefix)
            // Not a singleton itself: the import may still reach its COMPANION's member (`import Foo.bar`).
            else service.companionMembersFor(container, member ?: namePrefix)
        candidates.asSequence()
            .filter { member == null || it.name == member }
            .filter { it.isExtension && it.receiverTypeFqn != null && it.receiverTypeFqn in recvTargets }
            .forEach { out += bindMemberExtensionReceiver(it, receiverType) }
    }
    return out
}

/** Bind a member-extension's receiver type parameters from the actual [receiverType] (`fun <T> List<T>.x()`
 *  on `List<String>` → T = String), so its return/param types resolve concretely. */
internal fun KotlinResolver.bindMemberExtensionReceiver(
    ext: KotlinSymbol,
    receiverType: KotlinType
): KotlinSymbol {
    val bindings = HashMap<String, TypeRef>()
    ext.receiverTypeParam?.let { bindings[it] = receiverType }
    ext.receiverTypeArgs.forEachIndexed { i, ra ->
        val k = ra as? KotlinType ?: return@forEachIndexed
        if (k.isTypeParameter && i < receiverType.typeArguments.size) bindings[k.qualifiedName] =
            receiverType.typeArguments[i]
    }
    return if (bindings.isEmpty()) ext else service.substituteSymbol(ext, bindings)
}

/** A symbol for a member-extension declared in the LIVE buffer (`fun Map<…>.printMap()` inside a class),
 *  with its extension [receiverFqn] and receiver type-args set so it resolves/binds like an indexed one. */
internal fun KotlinResolver.sameFileMemberExtension(
    d: KtCallableDeclaration,
    receiverFqn: String
): KotlinSymbol? {
    val name = d.name ?: return null
    val recvArgs = service.typeFromText(
        d.receiverTypeReference?.text,
        fileContext
    )?.typeArguments ?: emptyList()
    val declNode = runCatching { parsed.adapt(d) }.getOrNull()
    return when (d) {
        is KtNamedFunction -> {
            val params = d.valueParameters.map { (it.name ?: "_") to it.typeReference?.text }
            val retText = d.typeReference?.text
            KotlinSymbol(
                name = name, kind = SymbolKind.METHOD,
                type = retText?.let { service.typeFromText(it, fileContext) },
                origin = SOURCE, receiverTypeFqn = receiverFqn,
                signature = "(" + params.joinToString(", ") { (n, t) -> "$n: ${t ?: "?"}" } + ")" + (retText?.let { ": $it" }
                    ?: ""),
                paramTypes = params.map { (_, t) -> service.typeFromText(t, fileContext) },
                paramNames = params.map { (n, _) -> n },
                paramHasDefault = d.valueParameters.map { it.hasDefaultValue() },
                varargParamIndex = d.valueParameters.indexOfFirst { it.isVarArg },
                isComposable = d.annotationEntries.any { it.shortName?.asString() == "Composable" },
                receiverTypeArgs = recvArgs,
                declarationNode = declNode,
            )
        }

        is KtProperty -> KotlinSymbol(
            name = name, kind = SymbolKind.FIELD,
            type = d.typeReference?.text?.let { service.typeFromText(it, fileContext) },
            origin = SOURCE, receiverTypeFqn = receiverFqn,
            signature = d.typeReference?.text?.let { ": $it" } ?: "",
            receiverTypeArgs = recvArgs,
            declarationNode = declNode,
        )

        else -> null
    }
}
