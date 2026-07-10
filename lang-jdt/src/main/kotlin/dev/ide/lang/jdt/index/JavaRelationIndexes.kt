package dev.ide.lang.jdt.index

import dev.ide.index.AnnotatedExternalizer
import dev.ide.index.AnnotatedValue
import dev.ide.index.AnnotationIndex
import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.StringKeyDescriptor
import dev.ide.index.SubtypeExternalizer
import dev.ide.index.SubtypeIndex
import dev.ide.index.SubtypeValue

/**
 * The JAVA-SOURCE producers of the direct-inheritor ([SubtypeIndex.JAVA_SOURCE]) and annotated-by
 * ([AnnotationIndex.JAVA_SOURCE]) indexes, over the binding-free [JavaSourceIndexer.sharedRelations]
 * model. Keys are SHORT names (the family contract; a structural parse sees `extends Base`, `@Test`);
 * values carry the best-effort import-resolved reference for filtering. See `IndexValues.kt`.
 */
private val javaSourceFilter =
    InputFilter { it.origin == IndexOrigin.SOURCE && it.unitName?.endsWith(".java") == true }

/** Best-effort FQN for a reference as written: import > dotted-as-FQN > same package. */
private fun resolve(name: String, pkg: String?, imports: Map<String, String>): String {
    val bare = name.substringBefore('<').trim()
    return imports[bare] ?: if ('.' in bare || pkg == null) bare else "$pkg.$bare"
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
                    SubtypeValue(t.fqn, t.kind.name.lowercase(), resolve(s, rel.packageName, rel.imports), input.fileId),
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
                AnnotatedValue(declFqn, kind, resolve(ann, rel.packageName, rel.imports), input.fileId),
            )
        }
        for (t in rel.types) {
            t.annotations.forEach { emit(t.fqn, t.kind.name.lowercase(), it) }
            t.memberAnnotations.forEach { m ->
                emit("${t.fqn}#${m.member}", m.kind.name.lowercase(), m.annotation)
            }
        }
        return out
    }
}
