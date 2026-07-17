package dev.ide.lang.java.index

import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocCommentOwner
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNameIdentifierOwner
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.SourceDocValue
import dev.ide.psi.IntellijPsiHost

/**
 * Structural (resolution-free) IntelliJ-PSI parse of one source file → its declarations + type relations, for
 * the source side of the Java indexes — the replacement for the JDT `ASTParser` structural parse. Parses on
 * the SHARED, classpath-free [IntellijPsiHost] environment (indexing needs only syntax: names/kinds/offsets/
 * visibility/nesting, supertype + annotation references AS WRITTEN, no bindings), matching the JDT indexer's
 * binding-free contract and emitting the identical [DeclKind] strings + offsets.
 *
 * The parse is shared per-input via [IndexInput.shared] (ONE [PsiJavaFile] for all of a file's indexes in a
 * pass) and content-cached across passes ([cache]). NOTE: PSI parsing serializes under the global parse lock,
 * so a large parallel index build funnels Java source parses through it — correct, but a known perf tradeoff
 * versus the JDT parser, and it is most pronounced for LIBRARY_SOURCE (JDK `src.zip` / Android sources); a
 * lighter stub-based indexer is the future optimization (as `:lang-kotlin-index` did for Kotlin).
 */
object JavaSourceIndexer {

    enum class DeclKind { CLASS, INTERFACE, ENUM, RECORD, ANNOTATION, METHOD, FIELD }

    data class Decl(val name: String, val kind: DeclKind, val offset: Int, val container: String?, val public: Boolean)
    data class Parsed(val packageName: String?, val decls: List<Decl>)

    /** One member's annotation use, for the annotated-by index: `owner#member` derives from [member]. */
    data class MemberAnnotation(val member: String, val kind: DeclKind, val annotation: String)

    /** A type's structural relations (references AS WRITTEN) for the subtype + annotation indexes. */
    data class TypeInfo(
        val fqn: String,
        val kind: DeclKind,
        val supertypes: List<String>,
        val annotations: List<String>,
        val memberAnnotations: List<MemberAnnotation>,
    )

    /** The file's type relations + its import map (simple name → FQN), shared across the indexes. */
    data class Relations(val packageName: String?, val types: List<TypeInfo>, val imports: Map<String, String>)

    /** Everything the SOURCE indexes need from ONE structural parse: declarations, relations, main entry points,
     *  and (for LIBRARY_SOURCE only) the per-owner source docs (param names + cleaned javadoc). */
    data class Extracted(
        val parsed: Parsed,
        val relations: Relations,
        val mains: List<Pair<String, Boolean>>,
        val docs: Map<String, Collection<SourceDocValue>> = emptyMap(),
    )

    private val EMPTY = Extracted(Parsed(null, emptyList()), Relations(null, emptyList(), emptyMap()), emptyList())

    private const val CACHE_MAX = 16
    private val cache = object : LinkedHashMap<String, Extracted>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Extracted>?) = size > CACHE_MAX
    }

    /**
     * Structurally parse [text] (bodies NOT materialized) under the shared parse lock and run [extract] on the
     * [PsiJavaFile]; null on parse failure. [extract] must return plain data (no PSI escapes the lock). This is
     * the light path all Java source indexing goes through — see [IntellijPsiHost.parseStructural].
     */
    fun <T> parseStructural(text: String, extract: (PsiJavaFile) -> T): T? =
        runCatching {
            IntellijPsiHost.parseStructural("Indexed.java", JavaLanguage.INSTANCE, text) { extract(it as PsiJavaFile) }
        }.getOrNull()

    /** All source-index data for [input], parsed ONCE per file per pass and shared across every source index.
     *  Source docs are extracted only for `LIBRARY_SOURCE` (the only origin `java.sourceDoc` indexes), so a
     *  project-source file isn't charged the javadoc walk. */
    fun sharedExtracted(input: IndexInput): Extracted =
        input.shared("java.extracted") {
            input.text()?.let { extractAll(it, withDocs = input.origin == IndexOrigin.LIBRARY_SOURCE) } ?: EMPTY
        }

    fun sharedParsed(input: IndexInput): Parsed = sharedExtracted(input).parsed
    fun sharedRelations(input: IndexInput): Relations = sharedExtracted(input).relations
    fun sharedMains(input: IndexInput): List<Pair<String, Boolean>> = sharedExtracted(input).mains
    fun sharedDocs(input: IndexInput): Map<String, Collection<SourceDocValue>> = sharedExtracted(input).docs

    /** Declarations of [text] (for input-less callers, e.g. IdeServices), content-cached across passes. */
    @Synchronized
    fun parse(text: String): Parsed {
        cache[text]?.let { return it.parsed }
        val extracted = extractAll(text)
        cache[text] = extracted
        return extracted.parsed
    }

    // `withDocs` is set only for LIBRARY_SOURCE inputs (JDK src.zip / sources-android-NN), whose sole index is
    // `java.sourceDoc`. That path consumes ONLY the docs, so we skip declsOf/relationsOf/mainsOf entirely there
    // (they would be walked and discarded on every SDK source file — pure waste on a large sources tree). The
    // SOURCE path is the inverse: it needs decls/relations/mains (six indexes share them) and never docs.
    private fun extractAll(text: String, withDocs: Boolean = false): Extracted =
        parseStructural(text) { psi ->
            if (withDocs) Extracted(EMPTY.parsed, EMPTY.relations, emptyList(), docsOf(psi))
            else Extracted(declsOf(psi), relationsOf(psi), JavaMainScan.mainsOf(psi))
        } ?: EMPTY

    // --- source docs (param names + cleaned javadoc; LIBRARY_SOURCE only) ---------------------------------

    fun docsOf(psi: PsiJavaFile): Map<String, Collection<SourceDocValue>> {
        val pkg = psi.packageName.ifEmpty { null }
        val out = HashMap<String, MutableList<SourceDocValue>>()
        val path = ArrayDeque<String>()

        fun docText(owner: PsiDocCommentOwner): String? =
            owner.docComment?.text?.let { JavaDoc.clean(it) }?.takeIf { it.isNotEmpty() }

        fun visit(cls: PsiClass) {
            val name = cls.name ?: return
            path.addLast(name)
            val fqn = if (pkg.isNullOrEmpty()) path.joinToString(".") else "$pkg.${path.joinToString(".")}"
            docText(cls)?.let { out.getOrPut(fqn) { ArrayList() }.add(SourceDocValue("", -1, emptyList(), it)) }
            for (m in cls.methods) {
                val params = m.parameterList.parameters.map { it.name }
                val memberName = if (m.isConstructor) name else m.name
                out.getOrPut(fqn) { ArrayList() }.add(SourceDocValue(memberName, params.size, params, docText(m)))
            }
            cls.innerClasses.forEach { visit(it) }
            path.removeLastOrNull()
        }
        psi.classes.forEach { visit(it) }
        return out
    }

    // --- declarations -------------------------------------------------------------------------------------

    fun declsOf(psi: PsiJavaFile?): Parsed {
        if (psi == null) return Parsed(null, emptyList())
        val pkg = psi.packageName.ifEmpty { null }
        val decls = ArrayList<Decl>()

        fun offsetOf(e: PsiNameIdentifierOwner): Int = e.nameIdentifier?.textOffset ?: 0
        fun isPublic(e: PsiModifierListOwner) = e.hasModifierProperty(PsiModifier.PUBLIC)

        fun visitClass(cls: PsiClass, container: String?) {
            val name = cls.name ?: return
            decls += Decl(name, kindOf(cls), offsetOf(cls), container, isPublic(cls))
            cls.methods.forEach { m: PsiMethod ->
                if (!m.isConstructor) decls += Decl(m.name, DeclKind.METHOD, offsetOf(m), name, isPublic(m))
            }
            cls.fields.forEach { f: PsiField -> decls += Decl(f.name, DeclKind.FIELD, offsetOf(f), name, isPublic(f)) }
            cls.innerClasses.forEach { visitClass(it, name) }
        }
        psi.classes.forEach { visitClass(it, null) }
        return Parsed(pkg, decls)
    }

    // --- relations ----------------------------------------------------------------------------------------

    fun relationsOf(psi: PsiJavaFile?): Relations {
        if (psi == null) return Relations(null, emptyList(), emptyMap())
        val pkg = psi.packageName.ifEmpty { null }
        val imports = HashMap<String, String>()
        psi.importList?.importStatements?.forEach { imp ->
            if (imp.isOnDemand) return@forEach
            val fqn = imp.qualifiedName ?: return@forEach
            imports[fqn.substringAfterLast('.')] = fqn
        }
        val types = ArrayList<TypeInfo>()
        val path = ArrayDeque<String>()

        fun annotationsOf(owner: PsiModifierListOwner): List<String> =
            owner.modifierList?.annotations?.mapNotNull { it.nameReferenceElement?.text } ?: emptyList()

        fun visitClass(cls: PsiClass) {
            val name = cls.name ?: return
            path.addLast(name)
            val fqn = (pkg?.plus(".") ?: "") + path.joinToString(".")
            val supers = ArrayList<String>()
            cls.extendsList?.referenceElements?.forEach { supers += it.text }
            cls.implementsList?.referenceElements?.forEach { supers += it.text }
            val memberAnns = ArrayList<MemberAnnotation>()
            cls.methods.forEach { m ->
                annotationsOf(m).forEach { memberAnns += MemberAnnotation(m.name, DeclKind.METHOD, it) }
            }
            cls.fields.forEach { f ->
                annotationsOf(f).forEach { memberAnns += MemberAnnotation(f.name, DeclKind.FIELD, it) }
            }
            types += TypeInfo(fqn, kindOf(cls), supers, annotationsOf(cls), memberAnns)
            cls.innerClasses.forEach { visitClass(it) }
            path.removeLastOrNull()
        }
        psi.classes.forEach { visitClass(it) }
        return Relations(pkg, types, imports)
    }

    internal fun kindOf(cls: PsiClass): DeclKind = when {
        cls.isAnnotationType -> DeclKind.ANNOTATION
        cls.isEnum -> DeclKind.ENUM
        cls.isInterface -> DeclKind.INTERFACE
        cls.isRecord -> DeclKind.RECORD
        else -> DeclKind.CLASS
    }
}
