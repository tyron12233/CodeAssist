package dev.ide.lang.java.env

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementFinder
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.search.GlobalSearchScope
import dev.ide.lang.java.synthetic.JavaSyntheticSource
import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.platform.ContentHash

/**
 * A [PsiElementFinder] on the resolution env's project that resolves Java classes with NO file on disk:
 *   • **synthetic** classes — Android `R`/`BuildConfig`, ViewBinding, Kotlin light classes (from
 *     `SYNTHETIC_CLASS_EP`), rendered to Java source and parsed; and
 *   • **open-buffer overlay** — a project source file with unsaved editor edits (FQN → live text), so a
 *     dependent sees the edit before it is saved.
 *
 * The facade consults element finders in order; the built-in (disk source roots) is registered first, so for a
 * type that also exists on disk the disk copy wins — that means synthetic classes (no disk version) resolve
 * reliably, while the overlay wins only if this finder is consulted first (registered with FIRST order).
 *
 * Misses are cheap (a map + small-list lookup, no parse); a hit parses once, content-cached. Parsing goes
 * through [parse] (the env's locked full parse), so it is ART-safe and reentrant under the semantic pass.
 */
internal class JavaInjectedElementFinder(
    private val synthetic: () -> List<SyntheticClass>,
    private val overlay: () -> Map<String, CharArray>,
    private val parse: (name: String, text: CharSequence) -> PsiJavaFile,
) : PsiElementFinder() {

    // content hash -> parsed file (synthetic + overlay). Concurrent: findClass runs on many resolution
    // threads; parses serialize under the env's write lock, but hit-path reads must not race a write.
    private val cache = java.util.concurrent.ConcurrentHashMap<String, PsiJavaFile>()

    override fun findClass(qualifiedName: String, scope: GlobalSearchScope): PsiClass? {
        overlay()[qualifiedName]?.let { src ->
            classIn(parseCached(String(src), "${qualifiedName.substringAfterLast('.')}.java"), qualifiedName)?.let { return it }
        }
        for (c in synthetic()) {
            if (declares(c, qualifiedName)) {
                return classIn(parseCached(JavaSyntheticSource.emit(c), "${c.fqName.substringAfterLast('.')}.java"), qualifiedName)
            }
        }
        return null
    }

    override fun findClasses(qualifiedName: String, scope: GlobalSearchScope): Array<PsiClass> =
        findClass(qualifiedName, scope)?.let { arrayOf(it) } ?: PsiClass.EMPTY_ARRAY

    private fun parseCached(text: String, name: String): PsiJavaFile =
        cache.getOrPut(ContentHash.of(text).value) { parse(name, text) }

    private fun declares(c: SyntheticClass, fqn: String): Boolean =
        c.fqName == fqn || c.nestedClasses.any { declares(it, fqn) }

    /** The PsiClass named [fqn] anywhere in [file] (top-level or nested). */
    private fun classIn(file: PsiJavaFile, fqn: String): PsiClass? {
        fun walk(c: PsiClass): PsiClass? {
            if (c.qualifiedName == fqn) return c
            c.innerClasses.forEach { inner -> walk(inner)?.let { return it } }
            return null
        }
        file.classes.forEach { walk(it)?.let { return it } }
        return null
    }
}
