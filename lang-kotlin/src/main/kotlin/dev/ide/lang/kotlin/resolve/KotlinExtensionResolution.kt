package dev.ide.lang.kotlin.resolve

import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.TypeRef
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

/** Extension-callable resolution: member extensions in scope for a receiver type, receiver binding, and delegate-operator availability. */

/** Whether a `getValue`/`setValue` operator named [op] is available for a delegate of type [delegateType]:
 *  a plain member, or an in-scope extension. Returns true (don't flag) when none is modeled at all. */
internal fun KotlinResolver.delegateOperatorInScope(delegateType: KotlinType, op: String): Boolean {
    val candidates = service.membersNamed(delegateType.qualifiedName, delegateType.typeArguments, op)
        .filter { it.kind == SymbolKind.METHOD }
    if (candidates.isEmpty()) return true // operator not modeled on the classpath → conservative
    return candidates.any { !it.isExtension || extensionInScope(it) }
}

/** Whether the extension [sym] is in scope here — imported (explicit/star), same-package, or
 *  default-imported. No package info → don't guess a rejection. Mirrors `KotlinSourceAnalyzer.extensionInScope`. */
internal fun KotlinResolver.extensionInScope(sym: KotlinSymbol): Boolean {
    val pkg = sym.packageName ?: sym.declaringClassFqn?.substringBeforeLast('.', "")?.ifEmpty { null } ?: return true
    if (pkg == fileContext.packageName || dev.ide.lang.kotlin.symbols.DefaultImports.isDefaultImported(pkg)) return true
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
fun KotlinResolver.scopeMemberExtensions(offset: Int, receiverType: KotlinType, namePrefix: String = ""): List<KotlinSymbol> {
    if (receiverType.isTypeParameter) return emptyList()
    val recvTargets = (listOf(receiverType.qualifiedName) + service.supertypesOf(receiverType.qualifiedName)
        .filterIsInstance<KotlinType>().map { it.qualifiedName }).toHashSet()
    fun matches(n: String) = namePrefix.isEmpty() || n.startsWith(namePrefix, ignoreCase = true)
    val out = ArrayList<KotlinSymbol>()
    val liveOwners = HashSet<String>()
    // (a) Live-buffer enclosing classes/objects — covers an extension just typed in the file being edited
    //     (the disk-based symbol model may not carry it yet).
    var node: PsiElement? = elementAt(offset)
    while (node != null) {
        if (node is KtClassOrObject) {
            node.fqName?.asString()?.let { liveOwners += it }
            for (d in node.declarations) {
                if (d !is KtCallableDeclaration || d.receiverTypeReference == null) continue
                val name = d.name ?: continue
                if (!matches(name)) continue
                val recvFqn = service.resolveTypeName(d.receiverTypeReference!!.text, fileContext) ?: continue
                if (recvFqn !in recvTargets) continue
                sameFileMemberExtension(d, recvFqn)?.let { out += bindMemberExtensionReceiver(it, receiverType) }
            }
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
    return out
}

/** Bind a member-extension's receiver type parameters from the actual [receiverType] (`fun <T> List<T>.x()`
 *  on `List<String>` → T = String), so its return/param types resolve concretely. */
internal fun KotlinResolver.bindMemberExtensionReceiver(ext: KotlinSymbol, receiverType: KotlinType): KotlinSymbol {
    val bindings = HashMap<String, TypeRef>()
    ext.receiverTypeParam?.let { bindings[it] = receiverType }
    ext.receiverTypeArgs.forEachIndexed { i, ra ->
        val k = ra as? KotlinType ?: return@forEachIndexed
        if (k.isTypeParameter && i < receiverType.typeArguments.size) bindings[k.qualifiedName] = receiverType.typeArguments[i]
    }
    return if (bindings.isEmpty()) ext else service.substituteSymbol(ext, bindings)
}

/** A symbol for a member-extension declared in the LIVE buffer (`fun Map<…>.printMap()` inside a class),
 *  with its extension [receiverFqn] and receiver type-args set so it resolves/binds like an indexed one. */
internal fun KotlinResolver.sameFileMemberExtension(d: KtCallableDeclaration, receiverFqn: String): KotlinSymbol? {
    val name = d.name ?: return null
    val recvArgs = (service.typeFromText(d.receiverTypeReference?.text, fileContext) as? KotlinType)?.typeArguments ?: emptyList()
    val declNode = runCatching { parsed.adapt(d) }.getOrNull()
    return when (d) {
        is KtNamedFunction -> {
            val params = d.valueParameters.map { (it.name ?: "_") to it.typeReference?.text }
            val retText = d.typeReference?.text
            KotlinSymbol(
                name = name, kind = SymbolKind.METHOD,
                type = retText?.let { service.typeFromText(it, fileContext) },
                origin = SOURCE, receiverTypeFqn = receiverFqn,
                signature = "(" + params.joinToString(", ") { (n, t) -> "$n: ${t ?: "?"}" } + ")" + (retText?.let { ": $it" } ?: ""),
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
