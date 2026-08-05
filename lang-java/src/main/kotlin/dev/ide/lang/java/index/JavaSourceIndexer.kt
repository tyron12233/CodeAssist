package dev.ide.lang.java.index

import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNameIdentifierOwner
import dev.ide.index.IndexInput
import dev.ide.psi.IntellijPsiHost

/**
 * Structural (resolution-free) IntelliJ-PSI parse of one source file → its declarations + type relations, for
 * the source side of the Java indexes — the replacement for the JDT `ASTParser` structural parse. Parses on
 * the SHARED, classpath-free [IntellijPsiHost] environment (indexing needs only syntax: names/kinds/offsets/
 * visibility/nesting, supertype + annotation references AS WRITTEN, no bindings), matching the JDT indexer's
 * binding-free contract and emitting the identical [DeclKind] strings + offsets.
 *
 * The parse is shared per-input via [IndexInput.shared] (ONE [PsiJavaFile] for all of a file's indexes in a
 * pass) and content-cached across passes ([cache]). NOTE: PSI parsing serializes under the global parse lock
 * (concurrent `buildTree` is not ART-safe — see [IntellijPsiHost]), so a large parallel index build funnels
 * Java source parses through it — correct, but a known perf tradeoff versus the JDT parser. What keeps it
 * affordable is that the parse is genuinely structural: method bodies stay unexpanded chameleons, since nothing
 * here reads one. This runs ONLY for project `.java` SOURCE (declarations/relations/mains); `LIBRARY_SOURCE`
 * (JDK `src.zip` / Android sources) — once the dominant cost here — is now the lexer-based [JavaSourceDocScan]'s
 * job, off this parse and off the lock. A lighter stub-based indexer for the SOURCE side is a future step.
 */
object JavaSourceIndexer {

    enum class DeclKind { CLASS, INTERFACE, ENUM, RECORD, ANNOTATION, METHOD, FIELD }

    /**
     * One declaration. [static] and the method/field shape fields ([paramTypes]/[paramNames]/[returnType]/
     * [varargIndex]) are populated for METHOD (all of them) and FIELD ([static] + [returnType] = the field
     * type) declarations, so [JavaMembersByOwnerIndex] can carry a same-project Java member's real shape to
     * the Kotlin backend. Type texts are AS WRITTEN (`String[]`, `int`, `List<T>`, `String...`) — read from
     * the type element's source text, so the parse stays resolution-free. Empty/`-1`/false for type decls.
     */
    data class Decl(
        val name: String,
        val kind: DeclKind,
        val offset: Int,
        val container: String?,
        val public: Boolean,
        val static: Boolean = false,
        val paramNames: List<String> = emptyList(),
        val paramTypes: List<String> = emptyList(),
        val returnType: String? = null,
        val varargIndex: Int = -1,
    )
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

    /** Everything the project-SOURCE indexes need from ONE structural parse: declarations, relations, and main
     *  entry points. (Library-source docs are extracted separately by the lexer-based [JavaSourceDocScan].) */
    data class Extracted(
        val parsed: Parsed,
        val relations: Relations,
        val mains: List<Pair<String, Boolean>>,
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

    /** All source-index data for [input], parsed ONCE per file per pass and shared across every source index. */
    fun sharedExtracted(input: IndexInput): Extracted =
        input.shared("java.extracted") { input.text()?.let { extractAll(it) } ?: EMPTY }

    fun sharedParsed(input: IndexInput): Parsed = sharedExtracted(input).parsed
    fun sharedRelations(input: IndexInput): Relations = sharedExtracted(input).relations
    fun sharedMains(input: IndexInput): List<Pair<String, Boolean>> = sharedExtracted(input).mains

    /** Declarations of [text] (for input-less callers, e.g. IdeServices), content-cached across passes. */
    @Synchronized
    fun parse(text: String): Parsed {
        cache[text]?.let { return it.parsed }
        val extracted = extractAll(text)
        cache[text] = extracted
        return extracted.parsed
    }

    private fun extractAll(text: String): Extracted =
        parseStructural(text) { psi ->
            Extracted(declsOf(psi), relationsOf(psi), JavaMainScan.mainsOf(psi))
        } ?: EMPTY

    // --- declarations -------------------------------------------------------------------------------------

    fun declsOf(psi: PsiJavaFile?): Parsed {
        if (psi == null) return Parsed(null, emptyList())
        val pkg = psi.packageName.ifEmpty { null }
        val decls = ArrayList<Decl>()

        fun offsetOf(e: PsiNameIdentifierOwner): Int = e.nameIdentifier?.textOffset ?: 0
        fun isPublic(e: PsiModifierListOwner) = e.hasModifierProperty(PsiModifier.PUBLIC)

        fun isStatic(e: PsiModifierListOwner) = e.hasModifierProperty(PsiModifier.STATIC)
        // The type element's raw source text — AS WRITTEN (`String[]`, `int`, `List<T>`, `String...`), no
        // resolution. Null typeElement (var-args synthesise none, an unusual malformed decl) falls back to "".
        fun typeText(e: com.intellij.psi.PsiTypeElement?): String = e?.text ?: ""

        fun visitClass(cls: PsiClass, container: String?) {
            val name = cls.name ?: return
            decls += Decl(name, kindOf(cls), offsetOf(cls), container, isPublic(cls), isStatic(cls))
            cls.methods.forEach { m: PsiMethod ->
                if (m.isConstructor) return@forEach
                val params = m.parameterList.parameters
                decls += Decl(
                    m.name, DeclKind.METHOD, offsetOf(m), name, isPublic(m), isStatic(m),
                    paramNames = params.map { it.name },
                    paramTypes = params.map { typeText(it.typeElement) },
                    returnType = m.returnTypeElement?.text,
                    varargIndex = params.indexOfFirst { it.isVarArgs },
                )
            }
            cls.fields.forEach { f: PsiField ->
                decls += Decl(
                    f.name, DeclKind.FIELD, offsetOf(f), name, isPublic(f), isStatic(f),
                    returnType = typeText(f.typeElement),
                )
            }
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
