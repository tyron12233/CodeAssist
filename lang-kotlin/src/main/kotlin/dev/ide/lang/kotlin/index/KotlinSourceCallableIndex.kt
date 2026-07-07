package dev.ide.lang.kotlin.index

import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.StringKeyDescriptor
import dev.ide.lang.kotlin.symbols.Builtins
import dev.ide.lang.resolve.SymbolKind
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

/**
 * `kotlin.callables.source` — the PROJECT-SOURCE counterpart of [KotlinCallableIndex], with the same key
 * scheme (`top:` / `ext:` / `name:`) and the same [CallableShape] value, produced from a resolution-free
 * PSI parse of each `.kt` file. This is what makes a source extension (`fun String.shout()` declared in one
 * file) appear on `"x".` in ANOTHER file straight after index sync — cross-file source callables used to
 * depend entirely on the in-memory source model being warm. Incremental like every source index (an edit
 * re-indexes one file), persisted across launches via the source cache.
 *
 * A separate index id (not extra SOURCE inputs on `kotlin.callables`) keeps the library index compiler-free:
 * this producer needs PSI, so it lives in lang-kotlin, while `kotlin.callables` stays loadable in the
 * isolated AA runtime.
 *
 * **Receiver resolution is best-effort and static** (no cross-file model at index time), in order: an
 * explicit import of the receiver's simple name, the default-import builtins (`String` → `kotlin.String`),
 * a dotted receiver text taken as an FQN, then the declaring file's package (`Box` in `package demo` →
 * `demo.Box`). A receiver that is one of the callable's own type parameters is keyed under its bound (or
 * `kotlin.Any`), mirroring how `@Metadata` keys `fun <T> T.also`. A wrong guess only wastes a key that no
 * query asks for; the warm source model still covers what static resolution can't.
 */
object KotlinSourceCallableIndex : IndexExtension<String, CallableShape> {
    override val id = IndexId("kotlin.callables.source")
    // Folds in the shared-codec FORMAT so a CallableShapeExternalizer change invalidates this in lockstep with
    // kotlin.callables / kotlin.builtinCallables (see CallableShapeExternalizer.FORMAT).
    override val version = 1 + CallableShapeExternalizer.FORMAT
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = CallableShapeExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter =
        InputFilter { it.origin == IndexOrigin.SOURCE && it.unitName?.endsWith(".kt") == true }

    override fun index(input: IndexInput): Map<String, Collection<CallableShape>> {
        val text = input.text() ?: return emptyMap()
        val name = input.sourcePath?.fileName?.toString() ?: input.unitName?.substringAfterLast('/') ?: "Source.kt"
        // The same shared parse the other Kotlin source indexes reuse (one PSI per file per pass).
        val kt = input.shared("kt.file") { KotlinMainScan.parse(name, text) } ?: return emptyMap()
        val pkg = kt.packageFqName.asString().ifEmpty { null }
        val imports = KotlinSourceNames.importsBySimpleName(kt)
        val out = HashMap<String, MutableList<CallableShape>>()
        for (d in kt.declarations) {
            val callable = d as? KtCallableDeclaration ?: continue
            if (callable !is KtNamedFunction && callable !is KtProperty) continue
            if (callable.hasModifier(KtTokens.PRIVATE_KEYWORD)) continue
            val callableName = callable.name ?: continue
            val shape = shapeOf(callable, callableName, pkg, imports) ?: continue
            val recv = shape.receiverFqn
            if (recv == null) {
                out.getOrPut(KotlinCallableIndex.topKey(callableName)) { ArrayList() }.add(shape)
            } else {
                out.getOrPut(KotlinCallableIndex.extKey(recv, callableName)) { ArrayList() }.add(shape)
                out.getOrPut(KotlinCallableIndex.nameKey(callableName)) { ArrayList() }.add(shape)
            }
        }
        return out
    }

    private fun shapeOf(
        c: KtCallableDeclaration,
        name: String,
        pkg: String?,
        imports: Map<String, String>,
    ): CallableShape? {
        val typeParams = c.typeParameters.mapNotNull { it.name }
        val receiverText = c.receiverTypeReference?.text
        var receiverTypeParam: String? = null
        val receiverFqn = when {
            receiverText == null -> null
            else -> {
                // Strip nullability + type arguments: `List<T>?` → `List` (args aren't part of the key).
                val simple = KotlinSourceNames.bareName(receiverText)
                if (simple in typeParams) {
                    // `fun <T> T.foo()` — keyed under the parameter's bound (or Any), like @Metadata.
                    receiverTypeParam = simple
                    val bound = c.typeParameters.firstOrNull { it.name == simple }?.extendsBound?.text
                        ?.let { KotlinSourceNames.bareName(it) }
                    bound?.let { KotlinSourceNames.resolve(it, pkg, imports) } ?: "kotlin.Any"
                } else KotlinSourceNames.resolve(simple, pkg, imports)
            }
        }
        if (receiverText != null && receiverFqn == null) return null

        val params = (c as? KtNamedFunction)?.valueParameters.orEmpty()
        val paramNames = params.map { it.name ?: "_" }
        val signature = when (c) {
            is KtNamedFunction -> {
                val ps = params.joinToString(", ") { "${it.name ?: "_"}: ${it.typeReference?.text ?: "Any"}" }
                val ret = c.typeReference?.text
                "($ps)" + (ret?.let { ": $it" } ?: "")
            }
            else -> (c as? KtProperty)?.typeReference?.text?.let { ": $it" }
        }
        return CallableShape(
            name = name,
            kind = if (c is KtNamedFunction) SymbolKind.METHOD else SymbolKind.FIELD,
            receiverFqn = receiverFqn,
            signature = signature,
            packageName = pkg,
            receiverTypeParam = receiverTypeParam,
            typeParameters = typeParams,
            // Types stay unresolved (a static parse can't bind them); the popup renders from the signature
            // text and the warm source model supplies real types once loaded.
            returnType = null,
            paramTypes = params.map { null },
            receiverTypeArgs = emptyList(),
            declaringClassFqn = null,
            paramNames = paramNames,
            isComposable = c.annotationEntries.any { it.shortName?.asString() == "Composable" },
            isInline = c.hasModifier(KtTokens.INLINE_KEYWORD),
            isInfix = c.hasModifier(KtTokens.INFIX_KEYWORD),
            isSuspend = c.hasModifier(KtTokens.SUSPEND_KEYWORD),
            varargParamIndex = params.indexOfFirst { it.hasModifier(KtTokens.VARARG_KEYWORD) },
            paramHasDefault = params.map { it.hasDefaultValue() },
            isDeprecated = c.annotationEntries.any { it.shortName?.asString() == "Deprecated" },
        )
    }

}
