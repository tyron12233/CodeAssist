package dev.ide.lang.java.index

import dev.ide.index.ClassNameExternalizer
import dev.ide.index.ClassNameValue
import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.InputFilter
import dev.ide.index.JavaSourceMemberCodec
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.MemberExternalizer
import dev.ide.index.MemberValue
import dev.ide.index.StringExternalizer
import dev.ide.index.StringKeyDescriptor
import dev.ide.index.SymbolExternalizer
import dev.ide.index.SymbolValue
import dev.ide.index.classEntryToFqn
import dev.ide.index.nestedClassEntryToFqn
import dev.ide.index.normalizedJarKey
import dev.ide.index.packagePrefixes
import dev.ide.lang.java.index.JavaSourceIndexer.DeclKind

/**
 * The Java workspace indexes, produced by the IntelliJ-PSI backend: library/SDK type names from class-entry
 * paths (visibility read from bytecode via ASM), members from ASM bytecode, and source declarations from a
 * structural PSI parse — the ecj-free replacements for `dev.ide.lang.jdt.index`. Same [IndexId]s/versions/
 * value shapes, so they are drop-in (the segments and every consumer, incl. the JDT compile name-env, are
 * unchanged).
 */

private fun isClassFile(i: IndexInput) =
    (i.origin == IndexOrigin.SDK || i.origin == IndexOrigin.LIBRARY) && i.unitName?.endsWith(".class") == true

// `.java` only: a `.kt` source needs the Kotlin PSI, not the Java parser — Kotlin project source is indexed
// by the parallel `kotlin.*` producers in lang-kotlin (KotlinWorkspaceIndexes), which consumers merge via
// [dev.ide.index.ClassNameIndex] etc. (Binary Kotlin still flows through `isClassFile`, read from bytecode.)
private fun isSource(i: IndexInput) =
    i.origin == IndexOrigin.SOURCE && i.unitName?.endsWith(".java") == true

private val TYPE_KINDS =
    setOf(DeclKind.CLASS, DeclKind.INTERFACE, DeclKind.ENUM, DeclKind.RECORD, DeclKind.ANNOTATION)

/** Cap on the outer-class chain a nested SOURCE type's FQN is built from: past any real nesting depth, and a
 *  bound on the climb even for a pathological file (`class A { class A { } }`). */
private const val MAX_NESTING = 8

/** The raw ASM [org.objectweb.asm.ClassReader] for [input], constructed ONCE per class and SHARED — via the
 *  cross-family [IndexInput.CLASS_READER] key — with the Kotlin binary indexes, so a library `.class`' constant
 *  pool is parsed a single time for the whole pass instead of once per index (≈6× on every android.jar class). */
private fun sharedReader(input: IndexInput): org.objectweb.asm.ClassReader? =
    input.shared(IndexInput.CLASS_READER) {
        val bytes = runCatching { input.bytes() }.getOrNull() ?: return@shared null
        runCatching { org.objectweb.asm.ClassReader(bytes) }.getOrNull()
    }

/** The ASM class shape for [input], distilled ONCE per class and SHARED across the `java.*` binary indexes that
 *  need it (`classNames`, `packageTypes`, `members`) via [IndexInput.shared] — over the [sharedReader]. The
 *  result (incl. a null for unreadable bytecode) is cached on the input. */
private fun sharedBytecode(input: IndexInput): JavaBytecode.ClassInfo? =
    input.shared("java.classfile") { sharedReader(input)?.let { JavaBytecode.read(it) } }

/** The declaration kind of a library/SDK class file if it declares a `public` type (JPMS gates packages, not
 *  the package-private types inside them), read from the ASM access flags; null when the type is non-public or
 *  the bytecode can't be read. A NESTED type's class-file flags carry its declared visibility except that
 *  `private` reads as package-private (so it is dropped, as it should be) and `protected` as public.
 *  Returning the kind (not just a boolean) lets the class-name indexes label a binary annotation/interface/
 *  enum correctly instead of the blanket `"class"` — so e.g. an `@`-annotation completion filter can tell
 *  `@Composable` from an ordinary class. */
private fun publicBytecodeKind(input: IndexInput): String? =
    sharedBytecode(input)?.takeIf { JavaBytecode.isPublic(it.access) }?.let { JavaBytecode.kindOf(it.access) }

/** classNames: simple type name -> FQN/origin/kind. Library/SDK from the entry path; source from the PSI parse. */
object JavaClassNamesIndex : IndexExtension<String, ClassNameValue> {
    override val id = IndexId("java.classNames")
    // v5: NESTED types are keyed by their own simple name with the dotted FQN an `import` spells
    // (`LayoutParams` -> `android.widget.LinearLayout.LayoutParams`), on both the binary and the source side.
    // Before this a nested library type was absent from the index entirely, so nothing offered to import
    // `LinearLayout.LayoutParams`/`Map.Entry` by name, and a nested SOURCE type was recorded under a wrong
    // (outer-less) FQN. The bump discards persisted `v4` partitions.
    override val version = 5
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = ClassNameExternalizer
    override val matching = MatchingMode.PREFIX_AND_FUZZY
    override val inputFilter = InputFilter { isClassFile(it) || isSource(it) }

    override fun index(input: IndexInput): Map<String, Collection<ClassNameValue>> {
        if (isClassFile(input)) {
            val kind = publicBytecodeKind(input) ?: return emptyMap()
            val entry = input.unitName!!
            val (fqn, simple) = classEntryToFqn(entry) ?: nestedClassEntryToFqn(entry) ?: return emptyMap()
            return mapOf(simple to listOf(ClassNameValue(fqn, input.origin, kind)))
        }
        val parsed = JavaSourceIndexer.sharedParsed(input)
        val types = parsed.decls.filter { it.kind in TYPE_KINDS }
        // A nested type's FQN is its whole outer chain (`app.Outer.Inner`), which [JavaSourceIndexer.Decl]
        // records one level at a time (`container` = the immediate outer's simple name), so climb it. First
        // declaration wins per name, and the climb is depth-capped, so a same-named nested type can't loop.
        val containerOf = HashMap<String, String?>(types.size)
        types.forEach { containerOf.putIfAbsent(it.name, it.container) }
        val out = HashMap<String, MutableList<ClassNameValue>>()
        for (d in types) {
            val chain = ArrayList<String>()
            var name: String? = d.name
            while (name != null && chain.size < MAX_NESTING) {
                chain.add(0, name)
                name = containerOf[name]
            }
            val fqn = (listOfNotNull(parsed.packageName?.ifEmpty { null }) + chain).joinToString(".")
            out.getOrPut(d.name) { ArrayList() }.add(ClassNameValue(fqn, IndexOrigin.SOURCE, d.kind.name.lowercase()))
        }
        return out
    }
}

/** classLocator: every library class FQN -> the normalized path of the owning jar (LIBRARY only). */
object JavaClassLocatorIndex : IndexExtension<String, String> {
    override val id = IndexId("java.classLocator")
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = StringExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter =
        InputFilter { it.origin == IndexOrigin.LIBRARY && it.unitName?.endsWith(".class") == true }

    override fun index(input: IndexInput): Map<String, Collection<String>> {
        val fqn = classEntryToFqn(input.unitName ?: return emptyMap())?.first ?: return emptyMap()
        val jar = input.sourcePath ?: return emptyMap()
        return mapOf(fqn to listOf(normalizedJarKey(jar)))
    }
}

/** packageTypes: package FQN -> the types directly in it (exact-package keyed, for `java.util.` completion). */
object JavaPackageTypesIndex : IndexExtension<String, ClassNameValue> {
    override val id = IndexId("java.packageTypes")
    // v5: a NESTED source type is no longer listed as a direct member of the package (it never was on the
    // binary side, whose `$` entries [classEntryToFqn] drops). The bump discards persisted `v4` partitions.
    override val version = 5
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = ClassNameExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter = InputFilter { isClassFile(it) || isSource(it) }

    override fun index(input: IndexInput): Map<String, Collection<ClassNameValue>> {
        if (isClassFile(input)) {
            val kind = publicBytecodeKind(input) ?: return emptyMap()
            val (fqn, _) = classEntryToFqn(input.unitName!!) ?: return emptyMap()
            val pkg = fqn.substringBeforeLast('.', "").ifEmpty { return emptyMap() }
            return mapOf(pkg to listOf(ClassNameValue(fqn, input.origin, kind)))
        }
        val parsed = JavaSourceIndexer.sharedParsed(input)
        val pkg = parsed.packageName?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        val out = HashMap<String, MutableList<ClassNameValue>>()
        for (d in parsed.decls) {
            if (d.kind !in TYPE_KINDS || d.container != null) continue // a nested type is not IN the package
            out.getOrPut(pkg) { ArrayList() }
                .add(ClassNameValue("$pkg.${d.name}", IndexOrigin.SOURCE, d.kind.name.lowercase()))
        }
        return out
    }
}

/** packages: package FQN -> itself; every prefix of every accessible class FQN. */
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

/** members: member name -> owner FQN/kind/signature. Library/SDK from ASM bytecode, source from the PSI parse. */
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
        val owner = input.unitName?.removeSuffix(".class")?.replace('/', '.')?.takeIf { '$' !in it }
            ?: return emptyMap()
        val info = sharedBytecode(input) ?: return emptyMap()
        val out = HashMap<String, MutableList<MemberValue>>()
        info.methods.forEach { m ->
            if (m.name == "<init>" || m.name == "<clinit>") return@forEach
            out.getOrPut(m.name) { ArrayList() }.add(MemberValue(m.name, owner, "method", m.descriptor))
        }
        info.fields.forEach { f ->
            out.getOrPut(f.name) { ArrayList() }.add(MemberValue(f.name, owner, "field", f.descriptor))
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

/** membersByOwner: owner FQN -> its PUBLIC source members (so a Kotlin file can enumerate a same-project
 *  Java SOURCE class's members). `.java` source only, public-only. Each member carries its SHAPE — parameter
 *  types + names, the `static` flag, the return type — encoded into [MemberValue.signature] via
 *  [JavaSourceMemberCodec], so the Kotlin backend resolves a static call / instance member with real arity
 *  and types (not just a name). */
object JavaMembersByOwnerIndex : IndexExtension<String, MemberValue> {
    override val id = IndexId("java.membersByOwner")
    override val version = 2
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = MemberExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter =
        InputFilter { it.origin == IndexOrigin.SOURCE && it.unitName?.endsWith(".java") == true }

    override fun index(input: IndexInput): Map<String, Collection<MemberValue>> =
        membersFor(JavaSourceIndexer.sharedParsed(input))

    /** The public [parsed] members keyed by owner FQN, with each member's shape encoded into
     *  [MemberValue.signature]. Shared with the host's LIVE open-buffer provider (ide-core) so a Kotlin file
     *  sees an UNSAVED Java edit the same way it sees a saved (indexed) one — one producer, one decoder. */
    fun membersFor(parsed: JavaSourceIndexer.Parsed): Map<String, Collection<MemberValue>> {
        val out = HashMap<String, MutableList<MemberValue>>()
        for (d in parsed.decls) {
            if ((d.kind != DeclKind.METHOD && d.kind != DeclKind.FIELD) || !d.public) continue
            val container = d.container ?: continue
            val owner = if (parsed.packageName.isNullOrEmpty()) container else "${parsed.packageName}.$container"
            val sig = if (d.kind == DeclKind.METHOD)
                JavaSourceMemberCodec.encodeMethod(d.static, d.paramNames, d.paramTypes, d.returnType, d.varargIndex)
            else JavaSourceMemberCodec.encodeField(d.static, d.returnType)
            out.getOrPut(owner) { ArrayList() }.add(MemberValue(d.name, owner, d.kind.name.lowercase(), sig))
        }
        return out
    }
}

/** sourceSymbols: declaration name -> kind/file/offset/container over project source (go-to-symbol). */
object JavaSourceSymbolsIndex : IndexExtension<String, SymbolValue> {
    override val id = IndexId("java.sourceSymbols")
    override val version = 2
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = SymbolExternalizer
    override val matching = MatchingMode.PREFIX_AND_FUZZY
    override val inputFilter = InputFilter { isSource(it) }

    override fun index(input: IndexInput): Map<String, Collection<SymbolValue>> {
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
