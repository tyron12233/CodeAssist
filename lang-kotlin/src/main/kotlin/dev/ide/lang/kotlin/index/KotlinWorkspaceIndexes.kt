package dev.ide.lang.kotlin.index

import dev.ide.index.ClassNameExternalizer
import dev.ide.index.ClassNameIndex
import dev.ide.index.ClassNameValue
import dev.ide.index.IndexExtension
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.MemberExternalizer
import dev.ide.index.MemberValue
import dev.ide.index.MembersIndex
import dev.ide.index.PackageTypesIndex
import dev.ide.index.PackagesIndex
import dev.ide.index.SourceSymbolIndex
import dev.ide.index.StringExternalizer
import dev.ide.index.StringKeyDescriptor
import dev.ide.index.SymbolExternalizer
import dev.ide.index.SymbolValue
import dev.ide.index.packagePrefixes
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty

/**
 * The Kotlin SOURCE counterparts of the `java.*` workspace indexes (class names / go-to-symbol / package
 * contents / package names / members), produced from a resolution-free PSI parse of each `.kt` file. They
 * carry the SAME [IndexId] value shapes as the Java producers so the two are drop-in siblings — consumers
 * query both via [dev.ide.index.ClassNameIndex] / [dev.ide.index.SourceSymbolIndex] / … `.ALL` and merge.
 *
 * These are what let a project Kotlin class turn up in Java (and Kotlin) auto-import / type-name completion
 * and go-to-symbol; before the split, `.kt` files were (wrongly) fed to the Java parser. Binary Kotlin still
 * flows through the Java `isClassFile` branch (read from bytecode). All five reuse the ONE shared PSI parse
 * ([KotlinMainScan]) the other Kotlin source indexes already take, so a file is parsed once per pass.
 */

/** Parse (or reuse the shared parse of) [input] as a [KtFile]; null on failure / non-text input. */
private fun sharedKt(input: IndexInput): KtFile? {
    val text = input.text() ?: return null
    val name = input.sourcePath?.fileName?.toString() ?: input.unitName?.substringAfterLast('/') ?: "Source.kt"
    return input.shared("kt.file") { KotlinMainScan.parse(name, text) }
}

/** The ClassNameValue kind string for a Kotlin classifier (matches the KotlinSource{Subtype,Annotation} scans). */
private fun kindOf(c: KtClassOrObject): String = when {
    c is KtObjectDeclaration -> "object"
    c is KtClass && c.isInterface() -> "interface"
    c is KtClass && c.isEnum() -> "enum"
    c is KtClass && c.isAnnotation() -> "annotation"
    else -> "class"
}

/** The name-identifier offset for go-to-symbol navigation (falls back to the declaration start). */
private fun offsetOf(d: KtDeclaration): Int =
    (d as? KtNamedDeclaration)?.nameIdentifier?.textOffset ?: d.textOffset

/** Depth-first over every class/object (including nested), with each one's true (nested-aware) FQN + kind.
 *  A `KtEnumEntry` IS a `KtClassOrObject` (it extends `KtClass`), but an enum CONSTANT is a VALUE of the enum
 *  type, not a classifier — indexing `Direction.LEFT` as a source class made `isKnownType("Direction.LEFT")`
 *  true, so `Direction.LEFT` mis-resolved to a classifier and drew a spurious "does not have a companion
 *  object, and thus must be initialized here". Skipped here (its constants are surfaced via the symbol/callable
 *  side), matching [dev.ide.lang.kotlin.symbols.SourceIndex]'s `collectClasses`. */
private fun forEachType(kt: KtFile, action: (KtClassOrObject, String, String) -> Unit) {
    fun visit(decls: List<KtDeclaration>) {
        for (d in decls) {
            val c = (d as? KtClassOrObject)?.takeUnless { it is KtEnumEntry } ?: continue
            c.fqName?.asString()?.let { action(c, it, kindOf(c)) }
            visit(c.declarations)
        }
    }
    visit(kt.declarations)
}

/** `kotlin.classNames`: simple type name → FQN/origin/kind, for auto-import + type-name completion. */
object KotlinClassNamesIndex : IndexExtension<String, ClassNameValue> {
    override val id = ClassNameIndex.KOTLIN
    // v2: stopped indexing enum CONSTANTS as classifiers (see [forEachType]) — bump discards persisted `v1`
    // partitions that still hold the bad `Enum.CONSTANT` class entries for unchanged files.
    override val version = 2
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = ClassNameExternalizer
    override val matching = MatchingMode.PREFIX_AND_FUZZY
    override val inputFilter =
        InputFilter { it.origin == IndexOrigin.SOURCE && it.unitName?.endsWith(".kt") == true }

    override fun index(input: IndexInput): Map<String, Collection<ClassNameValue>> {
        val kt = sharedKt(input) ?: return emptyMap()
        val out = HashMap<String, MutableList<ClassNameValue>>()
        forEachType(kt) { c, fqn, kind ->
            val simple = c.name ?: return@forEachType
            out.getOrPut(simple) { ArrayList() }.add(ClassNameValue(fqn, IndexOrigin.SOURCE, kind))
        }
        return out
    }
}

/** `kotlin.packageTypes`: package FQN → the types directly in it (exact-package keyed). */
object KotlinPackageTypesIndex : IndexExtension<String, ClassNameValue> {
    override val id = PackageTypesIndex.KOTLIN
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = ClassNameExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter =
        InputFilter { it.origin == IndexOrigin.SOURCE && it.unitName?.endsWith(".kt") == true }

    override fun index(input: IndexInput): Map<String, Collection<ClassNameValue>> {
        val kt = sharedKt(input) ?: return emptyMap()
        val pkg = kt.packageFqName.asString().ifEmpty { return emptyMap() }
        val out = HashMap<String, MutableList<ClassNameValue>>()
        for (d in kt.declarations) {
            val c = d as? KtClassOrObject ?: continue
            val fqn = c.fqName?.asString() ?: continue
            out.getOrPut(pkg) { ArrayList() }.add(ClassNameValue(fqn, IndexOrigin.SOURCE, kindOf(c)))
        }
        return out
    }
}

/** `kotlin.packages`: package FQN → itself; every prefix of the file's package. */
object KotlinPackagesIndex : IndexExtension<String, String> {
    override val id = PackagesIndex.KOTLIN
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = StringExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter =
        InputFilter { it.origin == IndexOrigin.SOURCE && it.unitName?.endsWith(".kt") == true }

    override fun index(input: IndexInput): Map<String, Collection<String>> {
        val kt = sharedKt(input) ?: return emptyMap()
        val pkg = kt.packageFqName.asString().ifEmpty { return emptyMap() }
        return packagePrefixes("$pkg.X").associateWith { listOf(it) }
    }
}

/** `kotlin.sourceSymbols`: declaration name → kind/file/offset/container over project source (go-to-symbol). */
object KotlinSourceSymbolsIndex : IndexExtension<String, SymbolValue> {
    override val id = SourceSymbolIndex.KOTLIN
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = SymbolExternalizer
    override val matching = MatchingMode.PREFIX_AND_FUZZY
    override val inputFilter =
        InputFilter { it.origin == IndexOrigin.SOURCE && it.unitName?.endsWith(".kt") == true }

    override fun index(input: IndexInput): Map<String, Collection<SymbolValue>> {
        val fileId = input.fileId
        if (fileId < 0) return emptyMap()
        val kt = sharedKt(input) ?: return emptyMap()
        val out = HashMap<String, MutableList<SymbolValue>>()

        fun visit(decls: List<KtDeclaration>, container: String?) {
            for (d in decls) when (d) {
                is KtClassOrObject -> {
                    val name = d.name ?: continue
                    out.getOrPut(name) { ArrayList() }.add(SymbolValue(name, kindOf(d), fileId, offsetOf(d), container))
                    visit(d.declarations, name)
                }
                is KtNamedFunction -> {
                    val name = d.name ?: continue
                    out.getOrPut(name) { ArrayList() }.add(SymbolValue(name, "method", fileId, offsetOf(d), container))
                }
                is KtProperty -> {
                    val name = d.name ?: continue
                    out.getOrPut(name) { ArrayList() }.add(SymbolValue(name, "field", fileId, offsetOf(d), container))
                }
                else -> {}
            }
        }
        visit(kt.declarations, null)
        return out
    }
}

/** `kotlin.members`: member name → owner/kind/signature for source functions + properties (member search). */
object KotlinMembersIndex : IndexExtension<String, MemberValue> {
    override val id = MembersIndex.KOTLIN
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = MemberExternalizer
    override val matching = MatchingMode.PREFIX_AND_FUZZY
    override val inputFilter =
        InputFilter { it.origin == IndexOrigin.SOURCE && it.unitName?.endsWith(".kt") == true }

    override fun index(input: IndexInput): Map<String, Collection<MemberValue>> {
        val kt = sharedKt(input) ?: return emptyMap()
        val out = HashMap<String, MutableList<MemberValue>>()

        fun visit(decls: List<KtDeclaration>, owner: String) {
            for (d in decls) when (d) {
                is KtClassOrObject -> visit(d.declarations, d.name ?: owner)
                is KtNamedFunction -> {
                    val name = d.name ?: continue
                    out.getOrPut(name) { ArrayList() }.add(MemberValue(name, owner, "method", ""))
                }
                is KtProperty -> {
                    val name = d.name ?: continue
                    out.getOrPut(name) { ArrayList() }.add(MemberValue(name, owner, "field", ""))
                }
                else -> {}
            }
        }
        visit(kt.declarations, "")
        return out
    }
}
