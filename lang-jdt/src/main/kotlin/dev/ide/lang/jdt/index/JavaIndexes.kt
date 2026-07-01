package dev.ide.lang.jdt.index

import dev.ide.index.ClassNameExternalizer
import dev.ide.index.ClassNameValue
import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.MemberExternalizer
import dev.ide.index.MemberValue
import dev.ide.index.StringExternalizer
import dev.ide.index.StringKeyDescriptor
import dev.ide.index.SymbolExternalizer
import dev.ide.index.SymbolValue
import dev.ide.index.classEntryToFqn
import dev.ide.index.packagePrefixes
import dev.ide.lang.jdt.index.JavaSourceIndexer.DeclKind
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader

/**
 * The Java indexes, JDT-backed (the language backend is the producer, per the design): library/SDK type
 * names come from class-entry paths (visibility already enforced by the engine's JPMS export filter),
 * members from ecj bytecode, and all source declarations from a real structural parse — no regex.
 */

private fun isClassFile(i: IndexInput) =
    (i.origin == IndexOrigin.SDK || i.origin == IndexOrigin.LIBRARY) && i.unitName?.endsWith(".class") == true

private fun isSource(i: IndexInput) =
    i.origin == IndexOrigin.SOURCE && i.unitName?.let { it.endsWith(".java") || it.endsWith(".kt") } == true

private val TYPE_KINDS = setOf(DeclKind.CLASS, DeclKind.INTERFACE, DeclKind.ENUM, DeclKind.RECORD, DeclKind.ANNOTATION)

/**
 * Whether a library/SDK class file declares a `public` top-level type. JPMS exports gate *packages*, not
 * the package-private types inside an exported package, so the entry path alone isn't enough: e.g.
 * `java.util.ArraysParallelSortHelpers` is package-private and never referenceable from user code. We read
 * the class access flags (ecj's reader, the same one the members index uses) and skip non-public types so
 * they don't surface as completion candidates.
 */
private fun isPublicBytecodeType(input: IndexInput): Boolean {
    val reader = runCatching { ClassFileReader.read(input.bytes(), input.unitName) }.getOrNull() ?: return false
    return (reader.modifiers and ClassFileConstants.AccPublic) != 0
}

/** classNames: simple type name -> FQN/origin/kind. Library/SDK from the entry path; source from the AST. */
object JavaClassNamesIndex : IndexExtension<String, ClassNameValue> {
    override val id = IndexId("java.classNames")
    override val version = 3 // v3: skip package-private library/SDK types (see isPublicBytecodeType)
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = ClassNameExternalizer
    override val matching = MatchingMode.PREFIX_AND_FUZZY
    override val inputFilter = InputFilter { isClassFile(it) || isSource(it) }

    override fun index(input: IndexInput): Map<String, Collection<ClassNameValue>> {
        if (isClassFile(input)) {
            if (!isPublicBytecodeType(input)) return emptyMap()
            val (fqn, simple) = classEntryToFqn(input.unitName!!) ?: return emptyMap()
            return mapOf(simple to listOf(ClassNameValue(fqn, input.origin, "class")))
        }
        val parsed = JavaSourceIndexer.sharedParsed(input)
        val out = HashMap<String, MutableList<ClassNameValue>>()
        for (d in parsed.decls) {
            if (d.kind !in TYPE_KINDS) continue
            val fqn = if (parsed.packageName.isNullOrEmpty()) d.name else "${parsed.packageName}.${d.name}"
            out.getOrPut(d.name) { ArrayList() }.add(ClassNameValue(fqn, IndexOrigin.SOURCE, d.kind.name.lowercase()))
        }
        return out
    }
}

/**
 * classLocator: every library class FQN -> the absolute path of the owning jar. Unlike [JavaClassNamesIndex]
 * (curated for completion: simple-name keyed, public-only), this is the authoritative "which jar holds this
 * exact top-level type" map the JDT name environment uses to open exactly the owning jar instead of probing
 * every open jar, and to treat an empty result (when the index is ready) as a definitive not-on-classpath.
 * So it carries EVERY top-level library type, all visibilities (a package-private type is still resolvable
 * from its own package). LIBRARY only: jrt/SDK platform classes are served from the in-memory jrt image.
 * The value is the jar path, normalized identically to [normalizedJarKey] so a caller can match it against a
 * module's classpath entries (and filter out jars from other modules in the workspace-wide index).
 */
object JavaClassLocatorIndex : IndexExtension<String, String> {
    override val id = IndexId("java.classLocator")
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = StringExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter = InputFilter { it.origin == IndexOrigin.LIBRARY && it.unitName?.endsWith(".class") == true }

    override fun index(input: IndexInput): Map<String, Collection<String>> {
        val fqn = classEntryToFqn(input.unitName ?: return emptyMap())?.first ?: return emptyMap()
        val jar = input.sourcePath ?: return emptyMap()
        return mapOf(fqn to listOf(normalizedJarKey(jar)))
    }
}

/** Normalize a jar path to the stable string both the locator index and the name environment match on. */
internal fun normalizedJarKey(p: java.nio.file.Path): String =
    runCatching { p.toAbsolutePath().normalize().toString() }.getOrDefault(p.toString())

/** packageTypes: package FQN -> the types directly in it. Keyed by exact package, for `java.util.` completion. */
object JavaPackageTypesIndex : IndexExtension<String, ClassNameValue> {
    override val id = IndexId("java.packageTypes")
    override val version = 3 // v3: skip package-private library/SDK types (see isPublicBytecodeType)
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = ClassNameExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter = InputFilter { isClassFile(it) || isSource(it) }

    override fun index(input: IndexInput): Map<String, Collection<ClassNameValue>> {
        if (isClassFile(input)) {
            if (!isPublicBytecodeType(input)) return emptyMap()
            val (fqn, _) = classEntryToFqn(input.unitName!!) ?: return emptyMap()
            val pkg = fqn.substringBeforeLast('.', "").ifEmpty { return emptyMap() }
            return mapOf(pkg to listOf(ClassNameValue(fqn, input.origin, "class")))
        }
        val parsed = JavaSourceIndexer.sharedParsed(input)
        val pkg = parsed.packageName?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        val out = HashMap<String, MutableList<ClassNameValue>>()
        for (d in parsed.decls) {
            if (d.kind !in TYPE_KINDS) continue
            out.getOrPut(pkg) { ArrayList() }.add(ClassNameValue("$pkg.${d.name}", IndexOrigin.SOURCE, d.kind.name.lowercase()))
        }
        return out
    }
}

/** packages: package FQN -> itself; every prefix of every accessible class FQN. Prefix-only. */
object JavaPackagesIndex : IndexExtension<String, String> {
    override val id = IndexId("java.packages")
    override val version = 2
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = StringExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter = InputFilter { isClassFile(it) || isSource(it) }

    override fun index(input: IndexInput): Map<String, Collection<String>> {
        if (isClassFile(input)) {
            val fqn = classEntryToFqn(input.unitName!!)?.first ?: return emptyMap()
            return packagePrefixes(fqn).associateWith { listOf(it) }
        }
        val pkg = JavaSourceIndexer.sharedParsed(input).packageName?.takeIf { it.isNotEmpty() }
            ?: return emptyMap()
        return packagePrefixes("$pkg.X").associateWith { listOf(it) }
    }
}

/** members: member name -> owner FQN/kind/signature. Library/SDK from bytecode (incl. JDK), source from the AST. */
object JavaMembersIndex : IndexExtension<String, MemberValue> {
    override val id = IndexId("java.members")
    override val version = 2
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = MemberExternalizer
    override val matching = MatchingMode.PREFIX_AND_FUZZY
    override val inputFilter = InputFilter { isClassFile(it) || isSource(it) }

    override fun index(input: IndexInput): Map<String, Collection<MemberValue>> =
        if (isClassFile(input)) indexBytecode(input) else indexSource(input)

    private fun indexBytecode(input: IndexInput): Map<String, Collection<MemberValue>> {
        val owner = input.unitName?.removeSuffix(".class")?.replace('/', '.')?.takeIf { '$' !in it } ?: return emptyMap()
        val reader = runCatching { ClassFileReader.read(input.bytes(), input.unitName) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, MutableList<MemberValue>>()
        reader.methods?.forEach { m ->
            val sel = String(m.selector)
            if (sel == "<init>" || sel == "<clinit>") return@forEach
            out.getOrPut(sel) { ArrayList() }.add(MemberValue(sel, owner, "method", String(m.methodDescriptor)))
        }
        reader.fields?.forEach { f ->
            val name = String(f.name)
            out.getOrPut(name) { ArrayList() }.add(MemberValue(name, owner, "field", String(f.typeName)))
        }
        return out
    }

    private fun indexSource(input: IndexInput): Map<String, Collection<MemberValue>> {
        val out = HashMap<String, MutableList<MemberValue>>()
        for (d in JavaSourceIndexer.sharedParsed(input).decls) {
            if (d.kind != DeclKind.METHOD && d.kind != DeclKind.FIELD) continue
            out.getOrPut(d.name) { ArrayList() }
                .add(MemberValue(d.name, d.container ?: "", d.kind.name.lowercase(), ""))
        }
        return out
    }
}

/**
 * membersByOwner: owner FQN -> its PUBLIC source members. Lets a Kotlin file enumerate a same-project Java
 * SOURCE class's members (the name-keyed `java.members` index can't be queried by owner). `.java` source only
 * (the Kotlin backend models its own `.kt` source itself); public-only keeps visibility safe cross-file.
 */
object JavaMembersByOwnerIndex : IndexExtension<String, MemberValue> {
    override val id = IndexId("java.membersByOwner")
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = MemberExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter = InputFilter { it.origin == IndexOrigin.SOURCE && it.unitName?.endsWith(".java") == true }

    override fun index(input: IndexInput): Map<String, Collection<MemberValue>> {
        val parsed = JavaSourceIndexer.sharedParsed(input)
        val out = HashMap<String, MutableList<MemberValue>>()
        for (d in parsed.decls) {
            if ((d.kind != DeclKind.METHOD && d.kind != DeclKind.FIELD) || !d.public) continue
            val container = d.container ?: continue
            val owner = if (parsed.packageName.isNullOrEmpty()) container else "${parsed.packageName}.$container"
            out.getOrPut(owner) { ArrayList() }.add(MemberValue(d.name, owner, d.kind.name.lowercase(), ""))
        }
        return out
    }
}

/** sourceSymbols: declaration name -> kind/file/offset/container, over project source (go-to-symbol). */
object JavaSourceSymbolsIndex : IndexExtension<String, SymbolValue> {
    override val id = IndexId("java.sourceSymbols")
    override val version = 2
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = SymbolExternalizer
    override val matching = MatchingMode.PREFIX_AND_FUZZY
    override val inputFilter = InputFilter { isSource(it) }

    override fun index(input: IndexInput): Map<String, Collection<SymbolValue>> {
        // The file is referenced by its interned id (resolve via IndexService.filePath), not its path string.
        val fileId = input.fileId
        if (fileId < 0) return emptyMap()
        val out = HashMap<String, MutableList<SymbolValue>>()
        for (d in JavaSourceIndexer.sharedParsed(input).decls) {
            out.getOrPut(d.name) { ArrayList() }
                .add(SymbolValue(d.name, d.kind.name.lowercase(), fileId, d.offset, d.container))
        }
        return out
    }
}
