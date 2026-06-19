package dev.ide.lang.jdt

import dev.ide.lang.resolve.SourceDocProvider
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.SingleVariableDeclaration
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.readText

/**
 * Recovers method **parameter names** and **javadoc** by parsing Java source — information the compiled
 * bindings don't carry (`IMethodBinding` exposes parameter *types*, not names). Sources come from project
 * source dirs ([sourceDirs], mutable, content-hash cached) and from immutable source archives ([sourceJars]):
 * Maven `-sources.jar`s, the JDK `src.zip` (module-prefixed entries), and the Android platform `sources/`
 * dir. A syntax-only parse (no bindings, no environment scan) keeps it cheap; parses are cached.
 *
 * Exposed to other backends (the Kotlin editor) through the neutral [SourceDocProvider] SPI, so completing a
 * Java/Android API from a `.kt` file gets the same real parameter names + javadoc.
 */
class SourceMethodResolver(
    private val sourceDirs: List<Path>,
    private val sourceJars: List<Path> = emptyList(),
) : SourceDocProvider {
    /** A declared method's editor-facing facts. */
    data class MethodInfo(val params: List<String>, val javadoc: String?)

    /** One parsed Java file: method facts keyed `Type#method`, plus each type's own javadoc by simple name. */
    private class Parsed(val byKey: Map<String, List<MethodInfo>>, val typeDocs: Map<String, String>)

    private class FileEntry(val hash: Int, val parsed: Parsed)

    private val dirCache = HashMap<Path, FileEntry>()
    private val relCache = HashMap<String, Parsed?>() // archive sources are immutable
    private val jarIndex = HashMap<Path, Map<String, String>>() // jar → (relPath → full entry name)

    // --- SourceDocProvider (neutral SPI consumed by the Kotlin backend) ---

    override fun method(declaringFqn: String, methodName: String, arity: Int): SourceDocProvider.MethodDoc? =
        lookup(declaringFqn, methodName, arity)?.let { SourceDocProvider.MethodDoc(it.params, it.javadoc) }

    override fun classDoc(fqn: String): String? {
        val rel = topLevelFqn(fqn).replace('.', '/') + ".java"
        for (dir in sourceDirs) {
            val p = dir.resolve(rel)
            if (Files.isRegularFile(p)) entryForFile(p)?.parsed?.typeDocs?.get(simpleName(fqn))?.let { return it }
        }
        return relCache.getOrPut(rel) { parseFromJars(rel) }?.typeDocs?.get(simpleName(fqn))
    }

    /**
     * Look up [methodName] (use the simple class name for a constructor) declared on [declaringFqn],
     * preferring the overload whose parameter count is [arity]. Null when no source is found.
     */
    fun lookup(declaringFqn: String, methodName: String, arity: Int): MethodInfo? {
        val rel = topLevelFqn(declaringFqn).replace('.', '/') + ".java"
        for (dir in sourceDirs) {
            val p = dir.resolve(rel)
            if (Files.isRegularFile(p)) {
                entryForFile(p)?.let { e -> pick(e.parsed.byKey, declaringFqn, methodName, arity)?.let { return it } }
            }
        }
        val byKey = relCache.getOrPut(rel) { parseFromJars(rel) }?.byKey ?: return null
        return pick(byKey, declaringFqn, methodName, arity)
    }

    private fun pick(byKey: Map<String, List<MethodInfo>>, declaringFqn: String, methodName: String, arity: Int): MethodInfo? {
        val candidates = byKey[simpleName(declaringFqn) + "#" + methodName] ?: return null
        return candidates.firstOrNull { it.params.size == arity } ?: candidates.firstOrNull()
    }

    private fun entryForFile(file: Path): FileEntry? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val hash = text.hashCode()
        dirCache[file]?.let { if (it.hash == hash) return it }
        return FileEntry(hash, parse(text)).also { dirCache[file] = it }
    }

    private fun parseFromJars(rel: String): Parsed? {
        for (jar in sourceJars) {
            val full = indexFor(jar)[rel] ?: continue
            val text = readEntry(jar, full) ?: continue
            return parse(text)
        }
        return null
    }

    /** rel → full entry name. Includes the entry's own name and its module-prefix-stripped form, so both
     *  Maven (`okhttp3/X.java`) and JDK `src.zip` (`java.base/java/util/X.java`) resolve by package-rel path. */
    private fun indexFor(jar: Path): Map<String, String> = jarIndex.getOrPut(jar) {
        val m = HashMap<String, String>()
        runCatching {
            ZipFile(jar.toFile()).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    val n = e.name
                    if (!n.endsWith(".java")) continue
                    m.putIfAbsent(n, n)
                    val stripped = n.substringAfter('/', "")
                    if (stripped.isNotEmpty() && stripped != n) m.putIfAbsent(stripped, n)
                }
            }
        }
        m
    }

    private fun readEntry(jar: Path, entryName: String): String? = runCatching {
        ZipFile(jar.toFile()).use { zf -> zf.getEntry(entryName)?.let { zf.getInputStream(it).readBytes().decodeToString() } }
    }.getOrNull()

    private fun parse(text: String): Parsed {
        val parser = ASTParser.newParser(AST.getJLSLatest())
        parser.setKind(ASTParser.K_COMPILATION_UNIT)
        parser.setResolveBindings(false)
        parser.setSource(text.toCharArray())
        val cu = runCatching { parser.createAST(null) as CompilationUnit }.getOrNull()
            ?: return Parsed(emptyMap(), emptyMap())
        val out = HashMap<String, MutableList<MethodInfo>>()
        val typeDocs = HashMap<String, String>()
        cu.accept(object : ASTVisitor() {
            override fun visit(md: MethodDeclaration): Boolean {
                val type = enclosingTypeName(md) ?: return true
                @Suppress("UNCHECKED_CAST")
                val params = (md.parameters() as List<SingleVariableDeclaration>).map { it.name.identifier }
                val doc = md.javadoc?.let { JavadocText.clean(it.toString()) }?.takeIf { it.isNotEmpty() }
                out.getOrPut(type + "#" + md.name.identifier) { ArrayList() }.add(MethodInfo(params, doc))
                return true
            }
        })
        cu.types().filterIsInstance<AbstractTypeDeclaration>().forEach { collectTypeDocs(it, typeDocs) }
        return Parsed(out, typeDocs)
    }

    /** A type's own javadoc, keyed by simple name (recursing into nested types). */
    private fun collectTypeDocs(td: AbstractTypeDeclaration, out: MutableMap<String, String>) {
        td.javadoc?.let { JavadocText.clean(it.toString()) }?.takeIf { it.isNotEmpty() }?.let { out.putIfAbsent(td.name.identifier, it) }
        td.bodyDeclarations().filterIsInstance<AbstractTypeDeclaration>().forEach { collectTypeDocs(it, out) }
    }

    private fun enclosingTypeName(md: MethodDeclaration): String? {
        var n = md.parent
        while (n != null) {
            if (n is AbstractTypeDeclaration) return n.name.identifier
            n = n.parent
        }
        return null
    }

    private fun topLevelFqn(fqn: String): String = fqn.substringBefore('$').substringBefore('<')
    private fun simpleName(fqn: String): String =
        fqn.substringBefore('<').substringAfterLast('.').substringAfterLast('$')
}
