package dev.ide.lang.jdt

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

/**
 * Recovers method **parameter names** and **javadoc** by parsing Java source — information the compiled
 * bindings don't carry (`IMethodBinding` exposes parameter *types*, not names). Sources come from project
 * source dirs ([sourceDirs], mutable, content-hash cached) and from immutable source archives ([sourceJars]):
 * Maven `-sources.jar`s, the JDK `src.zip` (module-prefixed entries), and the Android platform `sources/`
 * dir. A syntax-only parse (no bindings, no environment scan) keeps it cheap; parses are cached.
 */
class SourceMethodResolver(
    private val sourceDirs: List<Path>,
    private val sourceJars: List<Path> = emptyList(),
) {
    /** A declared method's editor-facing facts. */
    data class MethodInfo(val params: List<String>, val javadoc: String?)

    private class FileEntry(val hash: Int, val byKey: Map<String, List<MethodInfo>>)

    private val dirCache = HashMap<Path, FileEntry>()
    private val relCache = HashMap<String, Map<String, List<MethodInfo>>?>() // archive sources are immutable
    private val jarIndex = HashMap<Path, Map<String, String>>() // jar → (relPath → full entry name)

    /**
     * Look up [methodName] (use the simple class name for a constructor) declared on [declaringFqn],
     * preferring the overload whose parameter count is [arity]. Null when no source is found.
     */
    fun lookup(declaringFqn: String, methodName: String, arity: Int): MethodInfo? {
        val rel = topLevelFqn(declaringFqn).replace('.', '/') + ".java"
        for (dir in sourceDirs) {
            val p = dir.resolve(rel)
            if (Files.isRegularFile(p)) {
                entryForFile(p)?.let { e -> pick(e.byKey, declaringFqn, methodName, arity)?.let { return it } }
            }
        }
        val byKey = relCache.getOrPut(rel) { parseFromJars(rel) } ?: return null
        return pick(byKey, declaringFqn, methodName, arity)
    }

    private fun pick(byKey: Map<String, List<MethodInfo>>, declaringFqn: String, methodName: String, arity: Int): MethodInfo? {
        val candidates = byKey[simpleName(declaringFqn) + "#" + methodName] ?: return null
        return candidates.firstOrNull { it.params.size == arity } ?: candidates.firstOrNull()
    }

    private fun entryForFile(file: Path): FileEntry? {
        val text = runCatching { Files.readString(file) }.getOrNull() ?: return null
        val hash = text.hashCode()
        dirCache[file]?.let { if (it.hash == hash) return it }
        return FileEntry(hash, parse(text)).also { dirCache[file] = it }
    }

    private fun parseFromJars(rel: String): Map<String, List<MethodInfo>>? {
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

    private fun parse(text: String): Map<String, List<MethodInfo>> {
        val parser = ASTParser.newParser(AST.getJLSLatest())
        parser.setKind(ASTParser.K_COMPILATION_UNIT)
        parser.setResolveBindings(false)
        parser.setSource(text.toCharArray())
        val cu = runCatching { parser.createAST(null) as CompilationUnit }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, MutableList<MethodInfo>>()
        cu.accept(object : ASTVisitor() {
            override fun visit(md: MethodDeclaration): Boolean {
                val type = enclosingTypeName(md) ?: return true
                @Suppress("UNCHECKED_CAST")
                val params = (md.parameters() as List<SingleVariableDeclaration>).map { it.name.identifier }
                val doc = md.javadoc?.let { cleanJavadoc(it.toString()) }?.takeIf { it.isNotEmpty() }
                out.getOrPut(type + "#" + md.name.identifier) { ArrayList() }.add(MethodInfo(params, doc))
                return true
            }
        })
        return out
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

    /** Turn a raw `/** … */` javadoc into readable plain text: strip the comment markers and `@tag` lines,
     *  keep paragraph line breaks, cap the length (for a doc panel). */
    private fun cleanJavadoc(raw: String): String {
        val lines = raw.lineSequence()
            .map { it.trim().removePrefix("/**").removePrefix("/*").let { l -> if (l.endsWith("*/")) l.dropLast(2) else l }.trim().removePrefix("*").trim() }
            .toList()
        return lines
            .filterNot { it.startsWith("@") }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .take(2000)
    }
}
