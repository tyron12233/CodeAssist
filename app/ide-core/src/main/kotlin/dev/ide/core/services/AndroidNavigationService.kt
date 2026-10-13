package dev.ide.core.services

import dev.ide.android.support.index.AndroidResourceRefIndex
import dev.ide.android.support.index.ResourceDeclValue
import dev.ide.android.support.navigation.AndroidXmlTargets
import dev.ide.android.support.navigation.ResourceOccurrence
import dev.ide.android.support.navigation.ResourceOccurrences
import dev.ide.android.support.navigation.XmlNavTarget
import dev.ide.android.support.navigation.sanitizeResourceName
import dev.ide.android.support.resources.ResourceType
import dev.ide.core.EngineContext
import dev.ide.index.SourceSymbolIndex
import dev.ide.index.SymbolValue
import dev.ide.index.exactAll
import dev.ide.lang.kotlin.LibraryFile
import dev.ide.lang.kotlin.NavKind
import dev.ide.lang.kotlin.NavTarget
import dev.ide.lang.kotlin.libraryPath
import dev.ide.vfs.local.fileFor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import kotlin.io.path.readText

/**
 * Go to for Android: everything the Go to menu offers in a resource XML or manifest, and the resource half of
 * it in Java/Kotlin.
 *
 * - **Declaration** of a resource lists every variant (`values/`, `values-night/`, `layout-land/`, a library's
 *   AAR), a theme attribute or custom attribute goes to its `<attr>`, a style's `parent` to the parent style,
 *   and a class named in XML (custom view tag, manifest component, `tools:context`, fragment, nav destination)
 *   to its source, or its decompiled view when it comes from a library. `android:onClick` goes to the handler.
 * - **Usages** lists every `@type/name`, `?attr/name`, `R.type.name` and binding-class use across the project.
 * - **Related** pairs a layout with the classes that inflate it (`R.layout.x`, its binding class,
 *   `tools:context`) and a class with its layouts.
 *
 * The caret is classified by [AndroidXmlTargets] / [ResourceOccurrences], both pure; this service supplies the
 * project: the resource index, the source-symbol index and the files to sweep.
 */
internal class AndroidNavigationService(
    private val ctx: EngineContext,
    private val files: AndroidProjectFiles,
    /** The platform's `data/res` (a desktop SDK's), for framework resources and attributes; null on device. */
    private val frameworkResDir: () -> Path?,
) {

    /** What the caret is on in [file]: an XML target, or an `R.type.name` access in code. */
    fun targetAt(file: Path, text: String, offset: Int): XmlNavTarget? =
        if (isXml(file)) AndroidXmlTargets.at(file.toString(), text, offset, files.namespaceOf(file))
        else ResourceOccurrences.codeReferenceAt(text, offset)

    /** The targets for one [kind] at [offset]. Empty when nothing applies. */
    fun targets(file: Path, text: String, offset: Int, kind: NavKind): List<NavTarget> {
        if (files.androidModuleOf(file) == null) return emptyList()
        val target = targetAt(file, text, offset)
        return when (kind) {
            NavKind.DECLARATION -> target?.let { declarations(file, it, offset) }.orEmpty()
            NavKind.USAGES -> usages(file, text, offset, target)
            NavKind.RELATED -> related(file, text)
            else -> emptyList()
        }
    }

    /** Every navigation this service can answer at [offset], each with its targets. */
    fun options(file: Path, text: String, offset: Int): List<Pair<NavKind, List<NavTarget>>> {
        if (files.androidModuleOf(file) == null) return emptyList()
        val target = targetAt(file, text, offset)
        return buildList {
            target?.let { declarations(file, it, offset) }?.takeIf { it.isNotEmpty() }?.let { add(NavKind.DECLARATION to it) }
            usages(file, text, offset, target).takeIf { it.isNotEmpty() }?.let { add(NavKind.USAGES to it) }
            related(file, text).takeIf { it.isNotEmpty() }?.let { add(NavKind.RELATED to it) }
        }
    }

    // ---- Declaration --------------------------------------------------------------------------------------

    private fun declarations(file: Path, target: XmlNavTarget, offset: Int): List<NavTarget> = when (target) {
        is XmlNavTarget.Resource -> resourceDeclarations(target).filterNot { isAt(it, file, offset) }
        is XmlNavTarget.ClassRef -> classTargets(target.fqn)
        is XmlNavTarget.Attribute -> attributeDeclarations(target)
        is XmlNavTarget.OnClick -> onClickTargets(file, target)
    }

    /** Every declaration of a resource. A framework one is looked up in the SDK's `data/res` when present. */
    fun resourceDeclarations(target: XmlNavTarget.Resource): List<NavTarget> {
        if (target.isFramework) return frameworkDeclarations(target.type, target.name)
        if (target.packageName != null) return emptyList()
        return files.declarations(target.type, target.rName)
            .sortedWith(compareBy({ !files.isProjectResource(Paths.get(it.filePath)) }, { qualifierRank(it.filePath) }))
            .map { decl -> declTarget(decl) }
            .distinctBy { it.file.path to it.offset }
    }

    private fun attributeDeclarations(target: XmlNavTarget.Attribute): List<NavTarget> = when {
        target.isFramework -> frameworkDeclarations(ResourceType.ATTR, target.localName)
        target.prefix == null || target.prefix == "tools" || target.prefix == "xmlns" -> emptyList()
        else -> files.declarations(ResourceType.ATTR, sanitizeResourceName(target.localName)).map { declTarget(it) }
    }

    /** A declaration as a target: the index stores the declaring line, so land on the name within it. */
    private fun declTarget(decl: ResourceDeclValue): NavTarget {
        val path = Paths.get(decl.filePath)
        val offset = files.textOf(path)?.let { refineOffset(it, decl.offset, decl.name, decl.type) } ?: decl.offset
        return NavTarget(ctx.store.vfs.fileFor(path), offset, resourceFileLabel(path), "resource")
    }

    private fun frameworkDeclarations(type: ResourceType, name: String): List<NavTarget> {
        val res = frameworkResDir() ?: return emptyList()
        val out = ArrayList<NavTarget>()
        val fileTypes = setOf(ResourceType.LAYOUT, ResourceType.DRAWABLE, ResourceType.COLOR, ResourceType.ANIM,
            ResourceType.ANIMATOR, ResourceType.INTERPOLATOR, ResourceType.MENU, ResourceType.XML, ResourceType.MIPMAP)
        val dirs = runCatching { Files.list(res).use { s -> s.filter { Files.isDirectory(it) }.collect(Collectors.toList()) } }.getOrDefault(emptyList())
        if (type in fileTypes) {
            for (dir in dirs.filter { it.fileName.toString().substringBefore('-') == type.rClass }.sortedBy { it.fileName.toString().length }) {
                runCatching { Files.list(dir).use { s -> s.filter { it.fileName.toString().substringBefore('.') == name }.collect(Collectors.toList()) } }
                    .getOrDefault(emptyList()).forEach { out += NavTarget(ctx.store.vfs.fileFor(it), 0, resourceFileLabel(it), "resource") }
                if (out.size >= 8) break
            }
        }
        if (out.isEmpty()) {
            // Values: only the default configuration (`values/`), which holds every framework declaration once.
            val needle = "name=\"$name\""
            val values = res.resolve("values")
            // The file a type is conventionally declared in first (`attrs.xml`, `styles.xml`, ...), and stop at
            // the first hit: the SDK's `values/` is megabytes of XML.
            val likely = when (type) {
                ResourceType.ATTR -> "attrs"
                ResourceType.STYLE -> "styles"
                else -> type.rClass
            }
            val candidates = runCatching { Files.list(values).use { s -> s.filter { it.toString().endsWith(".xml") }.collect(Collectors.toList()) } }
                .getOrDefault(emptyList())
                .sortedBy { if (it.fileName.toString().startsWith(likely)) 0 else 1 }
            for (f in candidates) {
                if (out.isNotEmpty()) break
                val text = runCatching { f.readText() }.getOrNull() ?: continue
                var i = text.indexOf(needle)
                while (i >= 0) {
                    val tagStart = text.lastIndexOf('<', i)
                    val tag = text.substring(tagStart + 1, i).trim().substringBefore(' ')
                    val tagType = if (tag == "item") ResourceType.byRClass(Regex("""type="(\w+)"""").find(text.substring(tagStart, text.indexOf('>', i).coerceAtLeast(i)))?.groupValues?.get(1).orEmpty())
                    else ResourceType.fromValueTag(tag)
                    if (tagType == type || (type == ResourceType.ATTR && tag == "attr")) {
                        out += NavTarget(ctx.store.vfs.fileFor(f), i + "name=\"".length, resourceFileLabel(f) + " · android", "resource")
                        break
                    }
                    i = text.indexOf(needle, i + needle.length)
                }
            }
        }
        return out
    }

    /** A class named in XML: its project source, else (a library or framework class) its read-only view. */
    fun classTargets(fqn: String): List<NavTarget> {
        files.classLocation(fqn)?.let { (path, off) ->
            return listOf(NavTarget(ctx.store.vfs.fileFor(path), off, fqn.substringAfterLast('.'), "class"))
        }
        if (files.isLibraryClass(fqn)) return listOf(NavTarget(LibraryFile(libraryPath(fqn)), 0, fqn, "library"))
        return emptyList()
    }

    /** `android:onClick="m"`: `m` in the layout's `tools:context` class, else in any class that inflates it. */
    private fun onClickTargets(file: Path, target: XmlNavTarget.OnClick): List<NavTarget> {
        val classes = target.contextClass?.let { listOf(it) }
            ?: inflatingClasses(file).flatMap { (path, _) -> files.textOf(path)?.let { files.classesDeclaredIn(it) }.orEmpty() }
        val out = ArrayList<NavTarget>()
        for (fqn in classes.distinct()) {
            val (path, _) = files.classLocation(fqn) ?: continue
            val owner = fqn.substringAfterLast('.')
            ctx.indexService.exactAll<SymbolValue>(SourceSymbolIndex.ALL, target.method)
                .filter { it.kind == "method" && it.container == owner }
                .filter { ctx.indexService.filePath(it.fileId)?.let { p -> Paths.get(p).toAbsolutePath().normalize() } == path.toAbsolutePath().normalize() }
                .forEach { out += NavTarget(ctx.store.vfs.fileFor(path), it.offset, "$owner.${target.method}", "method") }
        }
        return out.distinctBy { it.file.path to it.offset }
    }

    // ---- Usages -------------------------------------------------------------------------------------------

    /** The resource at the caret, or (on a layout/menu/drawable's root tag) the file's own resource. */
    private fun usageSubject(file: Path, text: String, offset: Int, target: XmlNavTarget?): Pair<ResourceType, String>? {
        when (target) {
            is XmlNavTarget.Resource -> return if (target.packageName == null) target.type to target.name else null
            is XmlNavTarget.Attribute -> return if (!target.isFramework && target.prefix != null && target.prefix != "tools")
                ResourceType.ATTR to target.localName else null
            else -> {}
        }
        if (!isXml(file)) return null
        val own = AndroidXmlTargets.fileResource(file.toString()) ?: return null
        return own.takeIf { onRootTagName(text, offset) }
    }

    private fun usages(file: Path, text: String, offset: Int, target: XmlNavTarget?): List<NavTarget> {
        val (type, name) = usageSubject(file, text, offset, target) ?: return emptyList()
        return occurrences(type, name)
            .filter { (_, occ) -> occ.kind != ResourceOccurrence.Kind.DECLARATION }
            .map { (path, occ) -> usageTarget(path, occ) }
    }

    /**
     * Every occurrence of a resource across the project's resource XML, manifests and sources. The reference
     * index narrows the files to read; each is then scanned in its live text for the exact ranges. An id also
     * counts its field on the binding classes of the layouts that declare it (`binding.headerTitle`).
     */
    fun occurrences(type: ResourceType, name: String): List<Pair<Path, ResourceOccurrence>> {
        val idBindings = if (type == ResourceType.ID) idBindingClasses(name) else emptyList()
        val keys = buildList {
            add(ResourceOccurrences.key(type, name))
            if (type == ResourceType.LAYOUT) add(ResourceOccurrences.bindingKey(ResourceOccurrences.bindingClassName(sanitizeResourceName(name))))
            idBindings.forEach { add(ResourceOccurrences.bindingKey(it)) }
        }
        val out = ArrayList<Pair<Path, ResourceOccurrence>>()
        for (path in candidates(keys)) {
            val t = files.textOf(path) ?: continue
            val found = if (isXml(path)) ResourceOccurrences.inXml(t, type, name, isValuesFile(path))
            else ResourceOccurrences.inCode(t, type, name, idBindings)
            found.forEach { out += path to it }
        }
        return out
    }

    /** The binding classes of the project layouts that declare view id [id]. */
    private fun idBindingClasses(id: String): List<String> =
        files.declarations(ResourceType.ID, sanitizeResourceName(id))
            .map { Paths.get(it.filePath) }
            .filter { files.isProjectResource(it) && it.parent?.fileName?.toString()?.substringBefore('-') == "layout" }
            .mapNotNull { AndroidXmlTargets.fileResource(it.toString())?.second }
            .distinct()
            .map { ResourceOccurrences.bindingClassName(it) }

    /** The files that may name one of [keys]: the reference index's answer, or every project file before it's ready. */
    private fun candidates(keys: Collection<String>): List<Path> =
        files.filesNaming(keys) ?: (files.resourceXmlFiles() + files.sourceFiles())

    private fun usageTarget(path: Path, occ: ResourceOccurrence): NavTarget {
        val text = files.textOf(path).orEmpty()
        val at = occ.range.first.coerceIn(0, text.length)
        val line = text.substring(0, at).count { it == '\n' } + 1
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        val lineEnd = text.indexOf('\n', at).let { if (it < 0) text.length else it }
        val snippet = text.substring(lineStart, lineEnd).trim().let { if (it.length > 70) it.take(67) + "..." else it }
        return NavTarget(ctx.store.vfs.fileFor(path), at, "${path.fileName}:$line  $snippet", "usage")
    }

    // ---- Related ------------------------------------------------------------------------------------------

    private fun related(file: Path, text: String): List<NavTarget> =
        if (isXml(file)) relatedToResource(file, text) else relatedToClass(file, text)

    /** A layout's (or menu's) classes: those that name it (`R.layout.x`, its binding class), and `tools:context`. */
    private fun relatedToResource(file: Path, text: String): List<NavTarget> {
        val (type, name) = AndroidXmlTargets.fileResource(file.toString()) ?: return emptyList()
        if (type != ResourceType.LAYOUT && type != ResourceType.MENU) return emptyList()
        val out = ArrayList<NavTarget>()
        AndroidXmlTargets.toolsContext(file.toString(), text, files.namespaceOf(file))?.let { out += classTargets(it) }
        val users = if (type == ResourceType.LAYOUT) inflatingClasses(file) else
            candidates(listOf(ResourceOccurrences.key(type, name))).filterNot { isXml(it) }.mapNotNull { p ->
                files.textOf(p)?.let { t -> ResourceOccurrences.inCode(t, type, name).firstOrNull()?.let { p to it.range.first } }
            }
        users.forEach { (p, off) -> out += NavTarget(ctx.store.vfs.fileFor(p), off, p.fileName.toString(), "file") }
        return out.distinctBy { it.file.path }
    }

    /** Source files that use layout [layoutFile] (by `R.layout.x` or its binding class), with the first use. */
    private fun inflatingClasses(layoutFile: Path): List<Pair<Path, Int>> {
        val (type, name) = AndroidXmlTargets.fileResource(layoutFile.toString()) ?: return emptyList()
        if (type != ResourceType.LAYOUT) return emptyList()
        val keys = listOf(
            ResourceOccurrences.key(ResourceType.LAYOUT, name),
            ResourceOccurrences.bindingKey(ResourceOccurrences.bindingClassName(sanitizeResourceName(name))),
        )
        return candidates(keys).filterNot { isXml(it) }.mapNotNull { p ->
            val t = files.textOf(p) ?: return@mapNotNull null
            ResourceOccurrences.inCode(t, ResourceType.LAYOUT, name).firstOrNull()?.let { p to it.range.first }
        }
    }

    /** A class's layouts and menus: what it names (`R.layout.x`, a binding class), and layouts whose
     *  `tools:context` is a class this file declares. Every qualifier variant of each layout is listed. */
    private fun relatedToClass(file: Path, text: String): List<NavTarget> {
        val names = LinkedHashMap<Pair<ResourceType, String>, Unit>()
        ResourceOccurrences.referencedInCode(text)
            .filter { it.first == ResourceType.LAYOUT || it.first == ResourceType.MENU }
            .forEach { names[it] = Unit }
        val layouts = files.projectLayoutNames()
        ResourceOccurrences.bindingLayoutsIn(text, layouts).forEach { names[ResourceType.LAYOUT to it] = Unit }
        val declared = files.classesDeclaredIn(text).toSet()
        if (declared.isNotEmpty()) {
            val contextKeys = declared.map { AndroidResourceRefIndex.contextKey(it.substringAfterLast('.')) }
            for (path in files.filesNaming(contextKeys) ?: files.resourceXmlFiles()) {
                val (type, name) = AndroidXmlTargets.fileResource(path.toString()) ?: continue
                if (type != ResourceType.LAYOUT) continue
                val t = files.textOf(path) ?: continue
                if (!t.contains("tools:context")) continue
                if (AndroidXmlTargets.toolsContext(path.toString(), t, files.namespaceOf(path)) in declared) names[type to name] = Unit
            }
        }
        return names.keys.flatMap { (type, name) ->
            files.declarations(type, name).filter { files.isProjectResource(Paths.get(it.filePath)) }
                .sortedBy { qualifierRank(it.filePath) }
                .map { NavTarget(ctx.store.vfs.fileFor(Paths.get(it.filePath)), 0, resourceFileLabel(Paths.get(it.filePath)), "file") }
        }.distinctBy { it.file.path }
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    /** Whether the caret is on the first element's tag name (where the file's own resource is offered). */
    private fun onRootTagName(text: String, offset: Int): Boolean {
        var i = 0
        while (true) {
            i = text.indexOf('<', i)
            if (i < 0 || i + 1 >= text.length) return false
            val c = text[i + 1]
            if (c == '?' || c == '!') { i++; continue }
            var e = i + 1
            while (e < text.length && !text[e].isWhitespace() && text[e] != '>' && text[e] != '/') e++
            return offset in i + 1..e
        }
    }

    /** Whether target [t] is the caret's own position (a declaration doesn't list itself). */
    private fun isAt(t: NavTarget, file: Path, offset: Int): Boolean {
        if (Paths.get(t.file.path).toAbsolutePath().normalize() != file.toAbsolutePath().normalize()) return false
        val text = files.textOf(file) ?: return false
        val a = text.lastIndexOf('\n', (t.offset - 1).coerceAtLeast(0))
        val b = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0))
        return a == b
    }

    companion object {
        fun isXml(file: Path): Boolean = file.fileName?.toString()?.endsWith(".xml") == true

        fun isValuesFile(path: Path): Boolean =
            path.parent?.fileName?.toString()?.substringBefore('-') == "values"

        /** `values/strings.xml`, plus the library a resource came from when it isn't the project's. */
        fun resourceFileLabel(path: Path): String {
            val folder = path.parent?.fileName?.toString().orEmpty()
            val label = if (folder.isEmpty()) path.fileName.toString() else "$folder/${path.fileName}"
            val norm = path.toString().replace('\\', '/')
            val owner = norm.substringBeforeLast("/res/", "").substringAfterLast('/')
            val isProjectLike = norm.contains("/src/") && norm.contains("/res/")
            return if (owner.isNotEmpty() && !isProjectLike) "$label · $owner" else label
        }

        /** Default configuration first, then by qualifier length (`values` < `values-night` < `values-night-v31`). */
        fun qualifierRank(filePath: String): Int =
            Paths.get(filePath).parent?.fileName?.toString()?.length ?: 0

        /** The declaring line's offset → the name on it (`name="x"` or `@+id/x`), when it can be found there. */
        fun refineOffset(text: String, lineOffset: Int, name: String, type: String): Int {
            if (lineOffset !in 0..text.length) return lineOffset
            val lineEnd = text.indexOf('\n', lineOffset).let { if (it < 0) text.length else it }
            val line = text.substring(lineOffset, lineEnd)
            val dotted = name.replace('_', '.')
            for (needle in listOf("@+id/$name", "\"$name\"", "\"$dotted\"")) {
                val i = line.indexOf(needle)
                if (i >= 0) return lineOffset + i + if (needle.startsWith("@")) 5 else 1
            }
            // A multi-line start tag: the name may sit on a following line, before the tag closes.
            val close = text.indexOf('>', lineOffset).let { if (it < 0) text.length else it }
            val head = text.substring(lineOffset, close)
            val i = head.indexOf("\"$name\"")
            return if (i >= 0 && type != "layout") lineOffset + i + 1 else lineOffset
        }
    }
}
