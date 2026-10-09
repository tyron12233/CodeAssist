package dev.ide.lang.java.env

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementFinder
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPackage
import com.intellij.psi.impl.file.PsiPackageImpl
import com.intellij.psi.search.GlobalSearchScope
import dev.ide.lang.java.synthetic.JavaSyntheticSource
import dev.ide.lang.synthetic.SyntheticClass

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
 * ## Synthetic packages
 * A synthetic class can live in a package that exists ONLY through synthetic classes — most importantly
 * ViewBinding's `<namespace>.databinding`, which has no directory in any source root or classpath. `findClass`
 * by FQN alone is not enough to resolve `import <ns>.databinding.FooBinding;` or a qualified use of it: the
 * reference resolver first resolves the qualifier `<ns>.databinding` as a *package*, and if no finder reports
 * that package the whole reference stays unresolved (why R/BuildConfig — which live in the real `<namespace>`
 * source package — worked but ViewBinding did not). So this finder also reports the packages its synthetic
 * classes imply ([findPackage]/[getSubPackages]) and enumerates their classes ([getClasses]/[getClassNames]).
 * Only synthetic classes feed the package view — never the open-buffer overlay, whose files live in real
 * packages the built-in finder already enumerates (adding them here would duplicate the disk copy).
 *
 * Misses are cheap (a map + small-list lookup, no parse). A hit parses once and keeps one parsed file per
 * class name, reused while the source is the same array or the same synthetic class, or has equal content,
 * so a lookup neither copies nor hashes the buffer and the cache holds at most one file per name. Parsing
 * goes through [parse] (the env's locked full parse), so it is ART-safe and reentrant under the semantic pass.
 */
internal class JavaInjectedElementFinder(
    private val synthetic: () -> List<SyntheticClass>,
    private val overlay: () -> Map<String, CharArray>,
    private val parse: (name: String, text: CharSequence) -> PsiJavaFile,
    private val psiManager: () -> PsiManager,
) : PsiElementFinder() {

    private class OverlayParse(val source: CharArray, val file: PsiJavaFile)
    private class SyntheticParse(val source: SyntheticClass, val file: PsiJavaFile)

    // FQN -> parsed file, one per name. Concurrent: findClass runs on many resolution threads; parses
    // serialize under the env's write lock, but hit-path reads must not race a write.
    private val overlayParses = java.util.concurrent.ConcurrentHashMap<String, OverlayParse>()
    private val syntheticParses = java.util.concurrent.ConcurrentHashMap<String, SyntheticParse>()

    override fun findClass(qualifiedName: String, scope: GlobalSearchScope): PsiClass? {
        overlay()[qualifiedName]?.let { src ->
            classIn(overlayFile(qualifiedName, src), qualifiedName)?.let { return it }
        }
        for (c in synthetic()) {
            if (declares(c, qualifiedName)) return classIn(syntheticFile(c), qualifiedName)
        }
        return null
    }

    private fun overlayFile(fqn: String, src: CharArray): PsiJavaFile {
        overlayParses[fqn]?.let { cached ->
            if (cached.source === src) return cached.file
            if (cached.source.contentEquals(src)) {
                overlayParses[fqn] = OverlayParse(src, cached.file)
                return cached.file
            }
        }
        val file = parse("${fqn.substringAfterLast('.')}.java", String(src))
        overlayParses[fqn] = OverlayParse(src, file)
        return file
    }

    private fun syntheticFile(c: SyntheticClass): PsiJavaFile {
        syntheticParses[c.fqName]?.let { if (it.source === c) return it.file }
        val text = JavaSyntheticSource.emit(c)
        val previous = syntheticParses[c.fqName]?.file
        val file = previous?.takeIf { it.text == text } ?: parse("${c.fqName.substringAfterLast('.')}.java", text)
        syntheticParses[c.fqName] = SyntheticParse(c, file)
        return file
    }

    override fun findClasses(qualifiedName: String, scope: GlobalSearchScope): Array<PsiClass> =
        findClass(qualifiedName, scope)?.let { arrayOf(it) } ?: PsiClass.EMPTY_ARRAY

    /** A package the synthetic classes imply (their own package + every ancestor) — so a synthetic-only
     *  package like `<ns>.databinding` resolves as a package qualifier. `isValid()` on the returned package is
     *  just "project alive", and its class/sub-package enumeration is facade-aggregated by name, so returning
     *  one for a package that also exists on disk is harmless (the disk contents still surface). */
    override fun findPackage(qualifiedName: String): PsiPackage? =
        if (qualifiedName.isNotEmpty() && qualifiedName in syntheticPackages())
            PsiPackageImpl(psiManager(), qualifiedName)
        else null

    /** The synthetic sub-packages directly under [psiPackage] (e.g. `databinding` under `<namespace>`). */
    override fun getSubPackages(psiPackage: PsiPackage, scope: GlobalSearchScope): Array<PsiPackage> {
        val parent = psiPackage.qualifiedName
        val children = syntheticPackages().filter { it.substringBeforeLast('.', "") == parent && it != parent }
        if (children.isEmpty()) return PsiPackage.EMPTY_ARRAY
        return children.map { PsiPackageImpl(psiManager(), it) as PsiPackage }.toTypedArray()
    }

    /** The synthetic top-level classes declared directly in [psiPackage]. Only synthetic classes (no disk
     *  copy) are enumerated, so this never duplicates a class the built-in disk finder already returns. */
    override fun getClasses(psiPackage: PsiPackage, scope: GlobalSearchScope): Array<PsiClass> {
        val pkg = psiPackage.qualifiedName
        val classes = synthetic().filter { it.fqName.substringBeforeLast('.', "") == pkg }
        if (classes.isEmpty()) return PsiClass.EMPTY_ARRAY
        return classes.mapNotNull { findClass(it.fqName, scope) }.toTypedArray()
    }

    override fun getClassNames(psiPackage: PsiPackage, scope: GlobalSearchScope): MutableSet<String> {
        val pkg = psiPackage.qualifiedName
        return synthetic().asSequence()
            .filter { it.fqName.substringBeforeLast('.', "") == pkg }
            .map { it.fqName.substringAfterLast('.') }
            .toMutableSet()
    }

    /** Every package name implied by a synthetic class: its own package and all of that package's ancestors
     *  (so `<ns>.databinding` pulls in `<ns>`, `<ns>`'s parent, …, up to the top segment). The default package
     *  ("") is never reported. */
    private fun syntheticPackages(): Set<String> {
        val out = HashSet<String>()
        for (sc in synthetic()) {
            var pkg = sc.fqName.substringBeforeLast('.', "")
            while (pkg.isNotEmpty()) {
                if (!out.add(pkg)) break // this package (and thus its ancestors) already recorded
                pkg = pkg.substringBeforeLast('.', "")
            }
        }
        return out
    }

    /** Drop parsed synthetic/overlay files (a changed source already parses fresh, but a resource/synthetic
     *  change also needs the FACADE's class-resolution cache dropped, see [JavaEnvironment.dropCaches]; this
     *  releases the files the facade no longer reaches). */
    fun clearCache() {
        overlayParses.clear()
        syntheticParses.clear()
    }

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
