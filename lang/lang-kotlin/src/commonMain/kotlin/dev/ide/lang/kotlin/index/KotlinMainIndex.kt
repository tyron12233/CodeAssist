package dev.ide.lang.kotlin.index

import dev.ide.index.EntryPointExternalizer
import dev.ide.index.EntryPointIndex
import dev.ide.index.EntryPointValue
import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.StringKeyDescriptor
import dev.ide.kotlin.syntax.psi.KtAnnotated
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.platform.Lock

/**
 * Detects runnable `main` entry points in a `.kt` file from a real PSI parse (no resolution, no regex).
 * Recognizes: a top-level `fun main()` / `fun main(Array<String>)` (→ the file facade, e.g. `com.example.MainKt`,
 * honoring `@file:JvmName`); an `@JvmStatic fun main` in a top-level `object` or `companion object` (→ the
 * enclosing class, where the compiler emits the static method); and — as a convenience beyond the JVM launcher
 * — an *instance* `fun main` on a concrete top-level class with a no-arg constructor (the runner constructs it
 * and calls the method). Public so the run service can scan on demand while the index is still building.
 */
object KotlinMainScan {

    // Cross-pass content cache: (fileName, text) -> parsed KtFile. Index-owned and READ-ONLY, so a cached
    // tree cannot go stale (a LightSyntaxTree is immutable anyway). Keyed by filename too because the
    // file-facade name derives from it. Bounded, access-ordered LRU.
    //
    // Access order by hand: common Kotlin's LinkedHashMap is insertion-ordered and has no `removeEldestEntry`
    // hook, so a hit re-inserts its key to move it to the young end. At 16 entries that costs far less than
    // the parse it saves.
    private const val KT_CACHE_MAX = 16
    private val ktCache = LinkedHashMap<String, KtFile?>()
    private val ktCacheLock = Lock()

    /** Parse [text] as [fileName] to a [KtFile], content-cached across passes ([ktCache]); null on failure. */
    fun parse(fileName: String, text: String): KtFile? = ktCacheLock.withLock {
        val key = "$fileName $text"
        if (ktCache.containsKey(key)) {
            val hit = ktCache.remove(key)
            ktCache[key] = hit
            return@withLock hit
        }
        val kt = runCatching { KotlinParserHost.parse(fileName, text) }.getOrNull()
        ktCache[key] = kt
        while (ktCache.size > KT_CACHE_MAX) ktCache.remove(ktCache.keys.first())
        kt
    }

    /** Standalone scan of [text] (input-less callers, e.g. the run service's cold-start fallback). Inside an
     *  index, prefer [mainsOf] over the shared [KtFile] so the file is parsed once. */
    fun scan(fileName: String, text: String): List<Pair<String, Boolean>> = mainsOf(parse(fileName, text))

    /** Each hit: the class FQN to launch, paired with whether it must be invoked on an instance (no static main). */
    fun mainsOf(kt: KtFile?): List<Pair<String, Boolean>> {
        if (kt == null) return emptyList()
        val facade = facadeFqName(kt)
        val out = LinkedHashSet<Pair<String, Boolean>>()
        for (d in kt.declarations) when (d) {
            is KtNamedFunction -> if (isMainFun(d)) out.add(facade to false)
            is KtObjectDeclaration -> {           // top-level object O { @JvmStatic fun main }
                if (d.declarations.any { it is KtNamedFunction && isMainFun(it) && hasJvmStatic(it) }) {
                    d.fqName?.asString()?.let { out.add(it to false) }
                }
            }
            is KtClass -> {
                val fqn = d.fqName?.asString()
                if (fqn != null) {
                    // instance main: a concrete, instantiable class with a no-arg constructor.
                    if (isInstantiable(d) && hasNoArgCtor(d) &&
                        d.declarations.any { it is KtNamedFunction && isMainFun(it) && !hasJvmStatic(it) && !it.hasModifier(KtTokens.PRIVATE_KEYWORD) }
                    ) out.add(fqn to true)
                    // @JvmStatic main in the class's companion → a static method on the class.
                    val companion = d.declarations.filterIsInstance<KtObjectDeclaration>().firstOrNull { it.isCompanion() }
                    if (companion != null && companion.declarations.any { it is KtNamedFunction && isMainFun(it) && hasJvmStatic(it) }) {
                        out.add(fqn to false)
                    }
                }
            }
        }
        return out.toList()
    }

    /** A non-extension `main` taking no parameters or a single `Array<String>` — the JVM entry-point shapes. */
    private fun isMainFun(f: KtNamedFunction): Boolean {
        if (f.name != "main" || f.receiverTypeReference != null) return false
        val params = f.valueParameters
        return params.isEmpty() ||
            (params.size == 1 && params[0].typeReference?.text?.replace(" ", "")?.contains("Array<String>") == true)
    }

    private fun hasJvmStatic(a: KtAnnotated): Boolean =
        a.annotationEntries.any { it.shortName?.asString() == "JvmStatic" }

    private fun isInstantiable(c: KtClass): Boolean =
        !c.isInterface() && !c.isEnum() && !c.isAnnotation() &&
            !c.hasModifier(KtTokens.ABSTRACT_KEYWORD) && !c.hasModifier(KtTokens.SEALED_KEYWORD) &&
            !c.hasModifier(KtTokens.INNER_KEYWORD)

    /** A no-arg constructor exists (so `Class.getDeclaredConstructor().newInstance()` works): an empty primary,
     *  an implicit primary (no constructors declared), or an explicit no-arg secondary. */
    private fun hasNoArgCtor(c: KtClass): Boolean {
        val emptyPrimary = c.primaryConstructor != null && c.primaryConstructorParameters.isEmpty()
        val implicitPrimary = c.primaryConstructor == null && c.secondaryConstructors.isEmpty()
        val noArgSecondary = c.secondaryConstructors.any { it.valueParameters.isEmpty() }
        return emptyPrimary || implicitPrimary || noArgSecondary
    }

    /** The JVM file-facade FQN for [kt] (honoring `@file:JvmName`), e.g. `com.example.MainKt`. */
    private fun facadeFqName(kt: KtFile): String {
        val pkg = kt.packageFqName.asString()
        val jvmName = kt.fileAnnotationList?.annotationEntries
            ?.firstOrNull { it.shortName?.asString() == "JvmName" }
            ?.valueArguments?.firstOrNull()?.getArgumentExpression()?.text?.trim('"')?.takeIf { it.isNotEmpty() }
        val base = jvmName ?: facadeBase(kt.name)
        return if (pkg.isEmpty()) base else "$pkg.$base"
    }

    private fun facadeBase(fileName: String): String {
        val stem = fileName.substringAfterLast('/').removeSuffix(".kt")
        val sanitized = stem.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
        return sanitized.replaceFirstChar { it.uppercaseChar() } + "Kt"
    }
}

/** `kotlin.mains` — runnable entry points in project `.kt` source, keyed by [EntryPointIndex.KEY]. In-memory,
 *  source-side, rebuilt incrementally on edit (like the other source indexes). */
object KotlinMainIndex : IndexExtension<String, EntryPointValue> {
    override val id = IndexId("kotlin.mains")
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = EntryPointExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter =
        InputFilter { it.origin == IndexOrigin.SOURCE && it.unitName?.endsWith(".kt") == true }

    override fun index(input: IndexInput): Map<String, Collection<EntryPointValue>> {
        val fileId = input.fileId
        if (fileId < 0) return emptyMap()
        val text = input.text() ?: return emptyMap()
        val name = input.sourcePath?.substringAfterLast('/') ?: input.unitName?.substringAfterLast('/') ?: "Main.kt"
        // Reuse the KtFile parsed for this file: per-pass across Kotlin source indexes (input.shared), and
        // cross-pass for unchanged content (KotlinMainScan.parse's content cache).
        val kt = input.shared("kt.file") { KotlinMainScan.parse(name, text) }
        val hits = KotlinMainScan.mainsOf(kt)
        if (hits.isEmpty()) return emptyMap()
        return mapOf(EntryPointIndex.KEY to hits.map { (fqn, instance) -> EntryPointValue(fqn, fileId, instance) })
    }
}
