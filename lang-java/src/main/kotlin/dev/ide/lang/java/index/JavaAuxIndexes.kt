package dev.ide.lang.java.index

import dev.ide.index.AnnotatedExternalizer
import dev.ide.index.AnnotatedValue
import dev.ide.index.AnnotationIndex
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
import dev.ide.index.SourceDocExternalizer
import dev.ide.index.SourceDocValue
import dev.ide.index.StringKeyDescriptor
import dev.ide.index.SubtypeExternalizer
import dev.ide.index.SubtypeIndex
import dev.ide.index.SubtypeValue

/**
 * The IntelliJ-PSI producers of the Java-source auxiliary indexes — entry points (`main`), direct-inheritor
 * ([SubtypeIndex.JAVA_SOURCE]), annotated-by ([AnnotationIndex.JAVA_SOURCE]), and library-source doc (param
 * names + javadoc). Ecj-free replacements for the corresponding `dev.ide.lang.jdt.index` objects, over
 * [JavaSourceIndexer]'s binding-free PSI parse. Same [IndexId]s/versions/value shapes → drop-in.
 */

private val javaSourceFilter =
    InputFilter { it.origin == IndexOrigin.SOURCE && it.unitName?.endsWith(".java") == true }

/** Best-effort FQN for a reference as written: import > dotted-as-FQN > same package. */
private fun resolveRef(name: String, pkg: String?, imports: Map<String, String>): String {
    val bare = name.substringBefore('<').trim()
    return imports[bare] ?: if ('.' in bare || pkg == null) bare else "$pkg.$bare"
}

/** `java.mains` — runnable entry points in project `.java` source. */
object JavaMainIndex : IndexExtension<String, EntryPointValue> {
    override val id = IndexId("java.mains")
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = EntryPointExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter = javaSourceFilter

    override fun index(input: IndexInput): Map<String, Collection<EntryPointValue>> {
        val fileId = input.fileId
        if (fileId < 0) return emptyMap()
        val hits = JavaSourceIndexer.sharedMains(input)
        if (hits.isEmpty()) return emptyMap()
        return mapOf(EntryPointIndex.KEY to hits.map { (fqn, instance) -> EntryPointValue(fqn, fileId, instance) })
    }
}

object JavaSourceSubtypeIndex : IndexExtension<String, SubtypeValue> {
    override val id: IndexId = SubtypeIndex.JAVA_SOURCE
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = SubtypeExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter = javaSourceFilter

    override fun index(input: IndexInput): Map<String, Collection<SubtypeValue>> {
        val rel = JavaSourceIndexer.sharedRelations(input)
        if (rel.types.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<SubtypeValue>>()
        for (t in rel.types) {
            for (s in t.supertypes) {
                val bare = s.substringBefore('<').trim()
                out.getOrPut(SubtypeIndex.key(bare)) { ArrayList() }.add(
                    SubtypeValue(t.fqn, t.kind.name.lowercase(), resolveRef(s, rel.packageName, rel.imports), input.fileId),
                )
            }
        }
        return out
    }
}

object JavaSourceAnnotationIndex : IndexExtension<String, AnnotatedValue> {
    override val id: IndexId = AnnotationIndex.JAVA_SOURCE
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = AnnotatedExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter = javaSourceFilter

    override fun index(input: IndexInput): Map<String, Collection<AnnotatedValue>> {
        val rel = JavaSourceIndexer.sharedRelations(input)
        if (rel.types.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<AnnotatedValue>>()
        fun emit(declFqn: String, kind: String, ann: String) {
            out.getOrPut(AnnotationIndex.key(ann.substringAfterLast('.'))) { ArrayList() }.add(
                AnnotatedValue(declFqn, kind, resolveRef(ann, rel.packageName, rel.imports), input.fileId),
            )
        }
        for (t in rel.types) {
            t.annotations.forEach { emit(t.fqn, t.kind.name.lowercase(), it) }
            t.memberAnnotations.forEach { m -> emit("${t.fqn}#${m.member}", m.kind.name.lowercase(), m.annotation) }
        }
        return out
    }
}

/**
 * sourceDoc (Java): owner type FQN -> per-method real parameter NAMES + cleaned javadoc, recovered from an
 * attached `-sources.jar` / JDK `src.zip` / Android `sources/` (`LIBRARY_SOURCE`). A type's own javadoc is the
 * entry with an empty name; a constructor is keyed by the simple class name (matching the bytecode symbol).
 */
object JavaSourceDocIndex : IndexExtension<String, SourceDocValue> {
    override val id = IndexId("java.sourceDoc")
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = SourceDocExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter =
        InputFilter { it.origin == IndexOrigin.LIBRARY_SOURCE && it.unitName?.endsWith(".java") == true }

    // Reads the docs off the ONE shared structural parse (`sharedExtracted`) the other Java source indexes use,
    // rather than parsing the file a SECOND time — the dominant cost when indexing large LIBRARY_SOURCE trees
    // (JDK src.zip / Android sources-android-NN). The extraction itself lives in JavaSourceIndexer.docsOf.
    override fun index(input: IndexInput): Map<String, Collection<SourceDocValue>> =
        JavaSourceIndexer.sharedDocs(input)
}
