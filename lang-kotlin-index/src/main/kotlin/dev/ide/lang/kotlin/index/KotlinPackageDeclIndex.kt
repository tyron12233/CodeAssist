package dev.ide.lang.kotlin.index

import dev.ide.index.Externalizer
import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.StringKeyDescriptor
import dev.ide.index.classEntryToFqn
import dev.ide.lang.kotlin.symbols.KotlinMetadata
import java.io.DataInput
import java.io.DataOutput

/**
 * `kotlin.pkgDecls` — per-package enumeration of a library/SDK's **top-level Kotlin declarations**
 * (classifiers + top-level callables), the data the K2/Analysis-API STUBS-mode `KotlinDeclarationProvider`
 * needs to answer its hot-path queries WITHOUT eagerly decompiling every jar:
 *
 *  - `getTopLevelKotlinClassLikeDeclarationNamesInPackage(pkg)` / `getTopLevelCallableNamesInPackage(pkg)`
 *    → `exact(pkg)` filtered by [PkgDecl.classifier] (these name-sets gate `mayHaveTopLevelClassifier`/
 *    `mayHaveTopLevelCallable`, so they MUST be a superset of what the provider actually returns).
 *  - `getTopLevelFunctions`/`getTopLevelProperties((pkg, name))` → the matching entries' [PkgDecl.facade]
 *    tells the provider which facade `.class` to decompile (and then it extracts the `KtNamedFunction`/
 *    `KtProperty` named `name` — the function/property split is resolved by PSI type at decompile time,
 *    so it is NOT stored here).
 *  - `doesKotlinOnlyPackageExist(pkg)` → `exact(pkg)` is non-empty (a package with Kotlin declarations).
 *  - `getKotlinOnlySubpackageNames(pkg)` → `prefix("$pkg.")` keys, taking each key's next segment.
 *
 * Keyed by the package FqName (root package = `""`), `PREFIX_ONLY` so the subpackage scan is a prefix query
 * over the ordered keys. Class byte-location (ClassId → owning jar) is served by the existing
 * `java.classLocator` index, so this index carries no paths — only names + the facade FQN.
 *
 * Mirrors [KotlinCallableIndex] mechanically (decode `@kotlin.Metadata` per `.class`, persist per artifact,
 * block-cache, incremental) but keys by package rather than by callable name. No visibility filter: unlike
 * the completion-facing callable index, a declaration provider answers RESOLUTION (FIR applies visibility
 * later), so `internal`/`private` library declarations must still be enumerable — omitting one would make
 * the name-set gate wrongly drop a symbol.
 */
object KotlinPackageDeclIndex : IndexExtension<String, PkgDecl> {
    override val id = IndexId("kotlin.pkgDecls")
    override val version =
        2 // v2: + top-level typealiases (classifier entries carrying their facade FQN)
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = PkgDeclExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter = InputFilter {
        (it.origin == IndexOrigin.SDK || it.origin == IndexOrigin.LIBRARY) && it.unitName?.endsWith(
            ".class"
        ) == true
    }

    override fun index(input: IndexInput): Map<String, Collection<PkgDecl>> {
        val bytes = runCatching { input.bytes() }.getOrNull() ?: return emptyMap()
        // Only a Kotlin @Metadata class/facade contributes here; a plain Java/Android .class decodes to null.
        val decoded =
            runCatching { KotlinMetadata.decode(bytes, null) }.getOrNull() ?: return emptyMap()
        val unit = input.unitName ?: return emptyMap()
        val out = HashMap<String, MutableList<PkgDecl>>()

        if (decoded.classFqn != null) {
            // A top-level Kotlin class/object/interface/enum/annotation. A nested class is its own `$`-named
            // `.class` entry, for which classEntryToFqn returns null — skipped here: it is reached through its
            // top-level parent's decompiled file, not enumerated as a top-level classifier. (A class file
            // carries no top-level callables; its `decoded.extensions` are member extensions, not top-level.)
            classEntryToFqn(unit)?.let { (fqn, simple) ->
                out.getOrPut(fqn.substringBeforeLast('.', "")) { ArrayList() }
                    .add(PkgDecl(simple, classifier = true, facade = null))
            }
        } else {
            // A file facade / multi-file class part: every top-level callable — plain functions/properties
            // (`decoded.topLevel`) AND top-level extensions (`decoded.extensions`, e.g. `String.trim` in
            // kotlin.text). Both are top-level callables the provider's `getTopLevelFunctions`/`…Properties`
            // return, so both must be in the name-set (which gates `mayHaveTopLevelCallable`). The facade a
            // callable is invoked through is the part's declared facade (a multi-file part) or the `.class`
            // entry itself (a plain file facade) — that is the `.class` the provider decompiles.
            val entryFqn = unit.removeSuffix(".class").replace('/', '.')
            val facade = decoded.facadeClassFqn ?: entryFqn
            val pkg = unit.substringBeforeLast('/', "").replace('/', '.')
            (decoded.topLevel.asSequence() + decoded.extensions.asSequence()).forEach { s ->
                out.getOrPut(pkg) { ArrayList() }
                    .add(PkgDecl(s.name, classifier = false, facade = facade))
            }
            // Top-level typealiases are classifiers, but they have no `.class` of their own — they live in this
            // facade's @Metadata. Emit them as classifier entries CARRYING the facade, so the provider decompiles
            // the facade (not a nonexistent `.class`) to recover the KtTypeAlias, and the classifier name-set
            // includes them (else `mayHaveTopLevelClassifier` prunes a library typealias).
            decoded.typeAliasNames.forEach { ta ->
                out.getOrPut(pkg) { ArrayList() }
                    .add(PkgDecl(ta, classifier = true, facade = facade))
            }
        }
        return out
    }
}

/**
 * One enumerated top-level Kotlin declaration in a package: its [name], whether it is a [classifier]
 * (class/object/interface/enum/annotation/typealias) vs a top-level callable, and the owning [facade] class
 * FQN whose `.class` the declaration provider decompiles to recover the PSI. [facade] is set for callables
 * and for **typealiases** (which have no `.class` of their own — they live in the facade); it is null for a
 * real class/object/interface/enum/annotation, whose own `.class` is located by FQN via `java.classLocator`.
 */
class PkgDecl(
    val name: String,
    val classifier: Boolean,
    val facade: String?,
)

/** Context-free codec for a [PkgDecl]. */
object PkgDeclExternalizer : Externalizer<PkgDecl> {
    override fun write(out: DataOutput, value: PkgDecl) {
        out.writeUTF(value.name)
        out.writeBoolean(value.classifier)
        out.writeUTF(value.facade ?: "")
    }

    override fun read(inp: DataInput): PkgDecl {
        val name = inp.readUTF()
        val classifier = inp.readBoolean()
        val facade = inp.readUTF().ifEmpty { null }
        return PkgDecl(name, classifier, facade)
    }
}
