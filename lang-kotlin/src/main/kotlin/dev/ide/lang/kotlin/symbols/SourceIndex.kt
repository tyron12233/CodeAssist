package dev.ide.lang.kotlin.symbols

import dev.ide.lang.dom.DomNode
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.vfs.VirtualFile
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

/**
 * The project-source declaration index. Every `.kt` in the module is parsed to PSI (no resolution) and its
 * declarations captured as raw, type-resolution-free data: top-level functions/properties/classes, class
 * members, constructors, imports, and (since this is source) extension functions as first-class. Type
 * references are kept as TEXT here; the [KotlinSymbolService] resolves them to FQNs lazily once the whole
 * module is known (avoiding a build-order cycle).
 */

data class ImportInfo(val fqn: String, val alias: String?, val isStar: Boolean) {
    val simpleName: String get() = alias ?: fqn.substringAfterLast('.')
    val packageName: String get() = if (isStar) fqn else fqn.substringBeforeLast('.', "")
}

/** Per-file resolution context: the package + imports a type-text is resolved against. Shared by its decls. */
class FileContext(val path: String, val packageName: String, val imports: List<ImportInfo>)

class RawCallable(
    val name: String,
    val isFunction: Boolean,
    /** Extension receiver type text, e.g. "String" / "List<T>"; null ⇒ not an extension. */
    val receiverText: String?,
    /** Declared return (function) / declared type (property) text; null ⇒ inferred. */
    val returnText: String?,
    /** `= expr` body / property initializer text, for inferring an absent declared type. */
    val initializerText: String?,
    val paramTexts: List<Pair<String, String?>>,
    val ctx: FileContext,
    val node: DomNode?,
    /** `"private"` / `"protected"` / `"internal"` / null (public). */
    val visibility: String? = null,
)

class RawClass(
    val fqn: String,
    val simpleName: String,
    val isObject: Boolean,
    val superTypeTexts: List<String>,
    val members: List<RawCallable>,
    val constructors: List<RawCallable>,
    val ctx: FileContext,
    val node: DomNode?,
)

class SourceFile(
    val ctx: FileContext,
    val topLevel: List<RawCallable>,
    val extensions: List<RawCallable>,
    val classes: List<RawClass>,
)

class ModuleSourceModel(val files: List<SourceFile>) {
    val classByFqn: Map<String, RawClass> = files.flatMap { it.classes }.associateBy { it.fqn }
    val topLevel: List<RawCallable> = files.flatMap { it.topLevel }
    val extensions: List<RawCallable> = files.flatMap { it.extensions }

    companion object {
        val EMPTY = ModuleSourceModel(emptyList())
    }
}

object SourceIndexBuilder {

    fun build(sourceRoots: List<VirtualFile>, currentOverlay: Map<String, String> = emptyMap()): ModuleSourceModel {
        val files = ArrayList<SourceFile>()
        val seen = HashSet<String>()
        for (root in sourceRoots) walk(root) { vf ->
            if (vf.name.endsWith(".kt") && seen.add(vf.path)) {
                val text = currentOverlay[vf.path] ?: runCatching { vf.readText().toString() }.getOrNull()
                if (text != null) extract(vf, text)?.let(files::add)
            }
        }
        return ModuleSourceModel(files)
    }

    fun extract(vf: VirtualFile, text: String): SourceFile? {
        val kt = runCatching { KotlinParserHost.parse(vf.name, text) }.getOrNull() ?: return null
        val parsed = KotlinParsedFile(kt, vf, 0)
        return extractFrom(kt, parsed, vf.path)
    }

    fun extractFrom(kt: KtFile, parsed: KotlinParsedFile, path: String): SourceFile {
        val pkg = kt.packageFqName.asString()
        val imports = kt.importDirectives.mapNotNull { imp ->
            val fq = imp.importedFqName?.asString() ?: return@mapNotNull null
            ImportInfo(fq, imp.aliasName, imp.isAllUnder)
        }
        val ctx = FileContext(path, pkg, imports)

        val topLevel = ArrayList<RawCallable>()
        val extensions = ArrayList<RawCallable>()
        val classes = ArrayList<RawClass>()

        for (decl in kt.declarations) {
            when (decl) {
                is KtNamedFunction -> callable(decl, ctx, parsed).let { if (it.receiverText != null) extensions += it else topLevel += it }
                is KtProperty -> property(decl, ctx, parsed).let { if (it.receiverText != null) extensions += it else topLevel += it }
                is KtClassOrObject -> rawClass(decl, ctx, parsed)?.let { classes += it }
                else -> {}
            }
        }
        return SourceFile(ctx, topLevel, extensions, classes)
    }

    private fun rawClass(c: KtClassOrObject, ctx: FileContext, parsed: KotlinParsedFile): RawClass? {
        val fqn = c.fqName?.asString() ?: return null
        val supers = c.superTypeListEntries.mapNotNull { it.typeReference?.text }
        val members = ArrayList<RawCallable>()
        val ctors = ArrayList<RawCallable>()
        // Primary-constructor `val/var` params are member properties.
        c.primaryConstructorParameters.filter { it.hasValOrVar() }.forEach { p ->
            members += RawCallable(p.name ?: "_", false, null, p.typeReference?.text, null, emptyList(), ctx, node(parsed, p), visOf(p))
        }
        if (c.primaryConstructorParameters.isNotEmpty() || c.hasExplicitPrimaryConstructor()) {
            ctors += RawCallable(c.name ?: "", true, null, fqn, null,
                c.primaryConstructorParameters.map { (it.name ?: "_") to it.typeReference?.text }, ctx, node(parsed, c))
        }
        for (d in c.declarations) {
            when (d) {
                is KtNamedFunction -> members += callable(d, ctx, parsed)
                is KtProperty -> members += property(d, ctx, parsed)
                is KtClassOrObject -> {} // nested handled as their own top-level entries via fqName elsewhere
                else -> {}
            }
        }
        return RawClass(fqn, c.name ?: fqn.substringAfterLast('.'), c is org.jetbrains.kotlin.psi.KtObjectDeclaration,
            supers, members, ctors, ctx, node(parsed, c))
    }

    private fun callable(f: KtNamedFunction, ctx: FileContext, parsed: KotlinParsedFile) = RawCallable(
        name = f.name ?: "_",
        isFunction = true,
        receiverText = f.receiverTypeReference?.text,
        returnText = f.typeReference?.text,
        initializerText = if (f.typeReference == null) f.bodyExpression?.text else null,
        paramTexts = f.valueParameters.map { (it.name ?: "_") to it.typeReference?.text },
        ctx = ctx,
        node = node(parsed, f),
        visibility = visOf(f),
    )

    private fun property(p: KtProperty, ctx: FileContext, parsed: KotlinParsedFile) = RawCallable(
        name = p.name ?: "_",
        isFunction = false,
        receiverText = p.receiverTypeReference?.text,
        returnText = p.typeReference?.text,
        initializerText = if (p.typeReference == null) p.initializer?.text else null,
        paramTexts = emptyList(),
        ctx = ctx,
        node = node(parsed, p),
        visibility = visOf(p),
    )

    private fun visOf(decl: KtModifierListOwner): String? = when {
        decl.hasModifier(KtTokens.PRIVATE_KEYWORD) -> "private"
        decl.hasModifier(KtTokens.PROTECTED_KEYWORD) -> "protected"
        decl.hasModifier(KtTokens.INTERNAL_KEYWORD) -> "internal"
        else -> null
    }

    private fun node(parsed: KotlinParsedFile, psi: org.jetbrains.kotlin.com.intellij.psi.PsiElement): DomNode? =
        runCatching { parsed.adapt(psi) }.getOrNull()

    private fun walk(file: VirtualFile, onFile: (VirtualFile) -> Unit) {
        if (file.isDirectory) file.children().forEach { walk(it, onFile) } else onFile(file)
    }
}
