package dev.ide.core.services

import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.index.AndroidResourceIndex
import dev.ide.android.support.index.AndroidResourceRefIndex
import dev.ide.android.support.index.ResourceDeclValue
import dev.ide.android.support.resources.ResourceType
import dev.ide.core.EngineContext
import dev.ide.index.ClassNameIndex
import dev.ide.index.ClassNameValue
import dev.ide.index.SourceSymbolIndex
import dev.ide.index.SymbolValue
import dev.ide.index.exactAll
import dev.ide.model.ContentRole
import dev.ide.model.Module
import dev.ide.model.contentRootsFor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import kotlin.io.path.readText

/**
 * The project-wide file and index queries the Android navigation features share: which files to sweep for a
 * resource's uses (every Android module's `res/` XML and manifest, every module's `.java`/`.kt`), each one's
 * live text (the editor overlay wins over disk, so unsaved edits count), and where a resource or a class is
 * declared.
 *
 * Sweeps are by walking the module roots on demand rather than through an index: they only run when the user
 * asks (a Go to menu, a rename, a Quick Doc), and an on-device project is a few hundred files.
 */
internal class AndroidProjectFiles(private val ctx: EngineContext) {

    fun androidModules(): List<Module> = ctx.modules().filter { it.facets.get(AndroidFacet.KEY) != null }

    /** Every `res/**/*.xml` of every Android module, plus each module's manifest. */
    fun resourceXmlFiles(): List<Path> {
        val out = LinkedHashSet<Path>()
        for (m in androidModules()) {
            for (root in m.contentRootsFor(ContentRole.ANDROID_RES)) walk(root) { it.toString().endsWith(".xml") }.forEach { out.add(it) }
            manifestOf(m)?.takeIf { Files.isRegularFile(it) }?.let { out.add(it.toAbsolutePath().normalize()) }
        }
        return out.toList()
    }

    /** Every `.java` / `.kt` file under any module's source roots. */
    fun sourceFiles(): List<Path> {
        val out = LinkedHashSet<Path>()
        for (m in ctx.modules()) for (root in ctx.sourceRoots(m)) {
            walk(root) { val n = it.toString(); n.endsWith(".java") || n.endsWith(".kt") }.forEach { out.add(it) }
        }
        return out.toList()
    }

    /**
     * The files that may name any of [keys] (see [AndroidResourceRefIndex]): the index's hits, plus every open
     * buffer (its unsaved text isn't indexed yet) and every manifest (manifests aren't fed to the index). Null
     * while the index isn't ready; the caller then sweeps the whole project instead. Callers re-scan each
     * file's live text for the exact ranges, so an extra candidate costs a read, never a wrong answer.
     */
    fun filesNaming(keys: Collection<String>): List<Path>? {
        if (!ctx.indexService.status.ready) return null
        val out = LinkedHashSet<Path>()
        for (k in keys) ctx.indexService.exact<String>(AndroidResourceRefIndex.id, k)
            .forEach { out.add(Paths.get(it).toAbsolutePath().normalize()) }
        ctx.openDocumentPaths().filter { isXmlOrCode(it) }.forEach { out.add(it.toAbsolutePath().normalize()) }
        androidModules().mapNotNull { manifestOf(it) }.filter { Files.isRegularFile(it) }.forEach { out.add(it) }
        return out.toList()
    }

    private fun isXmlOrCode(p: Path): Boolean {
        val n = p.fileName?.toString() ?: return false
        return n.endsWith(".xml") || n.endsWith(".kt") || n.endsWith(".java")
    }

    /** The res roots of every Android module (project resources only, not AAR ones). */
    fun resourceRoots(): List<Path> =
        androidModules().flatMap { it.contentRootsFor(ContentRole.ANDROID_RES) }.map { it.toAbsolutePath().normalize() }

    /** Whether [path] is project resources (under an Android module's `res/` root), as opposed to a library's. */
    fun isProjectResource(path: Path): Boolean {
        val abs = path.toAbsolutePath().normalize()
        return resourceRoots().any { abs.startsWith(it) }
    }

    fun manifestOf(m: Module): Path? =
        m.facets.get(AndroidFacet.KEY)?.let { Paths.get(m.dir.path).resolve(it.manifest).toAbsolutePath().normalize() }

    /** The live text of [path]: the open editor buffer when there is one, else the file on disk. */
    fun textOf(path: Path): String? {
        val abs = path.toAbsolutePath().normalize()
        return ctx.overlayText(abs) ?: runCatching { abs.readText() }.getOrNull()
    }

    /** The Android module [file] belongs to (a resource, the manifest, or a source file), or null. */
    fun androidModuleOf(file: Path): Module? {
        val m = ctx.moduleForResourceFile(file) ?: ctx.moduleForEditableFile(file) ?: ctx.moduleForFile(file)
        return m?.takeIf { it.facets.get(AndroidFacet.KEY) != null }
    }

    /** The module's package (`namespace`), against which a manifest's `.MainActivity` resolves. */
    fun namespaceOf(file: Path): String? = androidModuleOf(file)?.facets?.get(AndroidFacet.KEY)?.namespace

    /** Every indexed declaration of a resource, project and library, in index order. */
    fun declarations(type: ResourceType, rName: String): List<ResourceDeclValue> =
        ctx.indexService.exact<ResourceDeclValue>(AndroidResourceIndex.id, AndroidResourceIndex.key(type.rClass, rName)).toList()

    /** Every layout name the project declares (the index keys under `layout/`). */
    fun projectLayoutNames(): Set<String> =
        ctx.indexService.prefix<ResourceDeclValue>(AndroidResourceIndex.id, "layout/")
            .map { it.value }
            .filter { isProjectResource(Paths.get(it.filePath)) }
            .mapTo(LinkedHashSet()) { it.name }

    /**
     * Where class [fqn] is declared in project source, as (file, offset of its name): looked up by simple name
     * in the go-to-symbol indexes, then confirmed against the file's `package` and the outer-class chain.
     */
    fun classLocation(fqn: String): Pair<Path, Int>? {
        val parts = fqn.split('.')
        val simple = parts.last()
        val hits = ctx.indexService.exactAll<SymbolValue>(SourceSymbolIndex.ALL, simple)
            .filter { it.kind in TYPE_KINDS }
        for (hit in hits) {
            val path = ctx.indexService.filePath(hit.fileId)?.let { Paths.get(it) } ?: continue
            val text = textOf(path) ?: continue
            val pkg = PACKAGE.find(text)?.groupValues?.get(1).orEmpty()
            val owner = listOfNotNull(pkg.ifEmpty { null }, hit.container).joinToString(".")
            if ((if (owner.isEmpty()) simple else "$owner.$simple") == fqn ||
                // A nested type's container is only its immediate outer: accept the package + any outer chain.
                (hit.container != null && fqn.startsWith("$pkg.") && fqn.endsWith(".${hit.container}.$simple"))
            ) {
                return path to hit.offset
            }
        }
        return null
    }

    /** Whether [fqn] is a compiled class on some module's classpath (a library or the platform). */
    fun isLibraryClass(fqn: String): Boolean =
        ctx.indexService.exactAll<ClassNameValue>(ClassNameIndex.ALL, fqn.substringAfterLast('.')).any { it.fqn == fqn }

    /** The top-level classes [text] (a `.java`/`.kt` buffer) declares, as FQNs. */
    fun classesDeclaredIn(text: String): List<String> {
        val pkg = PACKAGE.find(text)?.groupValues?.get(1).orEmpty()
        return TYPE_DECL.findAll(text).map { it.groupValues[1] }.distinct()
            .map { if (pkg.isEmpty()) it else "$pkg.$it" }.toList()
    }

    private fun walk(root: Path, accept: (Path) -> Boolean): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return runCatching {
            Files.walk(root).use { s ->
                s.filter { Files.isRegularFile(it) && accept(it) }.map { it.toAbsolutePath().normalize() }.collect(Collectors.toList())
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private val TYPE_KINDS = setOf("class", "interface", "object", "enum", "enum_class", "record", "annotation", "annotation_class")
        private val PACKAGE = Regex("""(?m)^\s*package\s+([\w.]+)""")
        private val TYPE_DECL = Regex("""(?m)^(?:[ \t]*(?:public|internal|private|protected|abstract|open|final|sealed|data|static)\s+)*(?:class|object|interface|enum\s+class|enum)\s+([A-Za-z_]\w*)""")
    }
}
