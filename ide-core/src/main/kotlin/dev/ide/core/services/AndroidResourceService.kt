package dev.ide.core.services

import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.index.AndroidResourceIndex
import dev.ide.android.support.index.ResourceDeclValue
import dev.ide.android.support.preview.AndroidColor
import dev.ide.android.support.preview.ColorEntry
import dev.ide.android.support.preview.ColorResources
import dev.ide.android.support.preview.DrawablePreview
import dev.ide.android.support.preview.DrawablePreviewParser
import dev.ide.android.support.preview.DrawableResolver
import dev.ide.android.support.preview.ResolvedDrawable
import dev.ide.android.support.resources.DrawableXmlCatalog
import dev.ide.android.support.resources.ResourceItem
import dev.ide.android.support.resources.ResourceReferences
import dev.ide.android.support.resources.ResourceRepository
import dev.ide.android.support.resources.ResourceType
import dev.ide.core.EngineContext
import dev.ide.model.ContentRole
import dev.ide.model.Module
import dev.ide.model.ModuleResources
import dev.ide.model.ResourceConflict
import dev.ide.model.ResourceEntry
import dev.ide.model.ResourceFilter
import dev.ide.model.ResourceWrite
import dev.ide.model.contentRootsFor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * WORKSPACE-scoped engine service: Android resource navigation, preview, query and authoring, carved out of
 * [dev.ide.core.IdeServices]. Go-to-definition for `@type/name` (res XML) and `R.type.name` (Java) references
 * (resolved purely through the resource index), the render-ready drawable model and the `<color>` swatch list
 * for the resource Preview, and the resource *authoring* the editor's "Create `@string/…`" fix runs on. The
 * `@color`/`@dimen`/`@drawable` reference resolver it needs is private here (nothing else uses it); the
 * resource-index feed the XML analyzer reads stays on the engine.
 *
 * Implements the published [ModuleResources] (`platform.moduleResources`), the plugin-facing slice: query
 * a module's resources, and add to them. The engine's own resource authoring used to be private to
 * [dev.ide.core.IdeServices] and shaped for exactly one quick fix: keyed on the file the fix ran in, always
 * de-duplicating by suffix, echoing the requested name back whether or not anything was written. Widening it
 * for a plugin meant naming the module, the conflict policy and the outcome, so the fix now goes through the
 * same members a plugin does rather than a private variant that could drift from them.
 */
internal class AndroidResourceService(private val ctx: EngineContext) : ModuleResources {

    /** Go-to-definition for the resource reference under [offset] in [file] ([text] = live buffer). */
    fun definitionAt(file: Path, text: String, offset: Int): Pair<Path, Int>? {
        val isXml = file.fileName?.toString()?.endsWith(".xml") == true
        val module = (if (isXml) ctx.moduleForResourceFile(file) else ctx.moduleForFile(file)) ?: return null
        if (module.facets.get(AndroidFacet.KEY) == null) return null
        val (type, name) = (if (isXml) xmlResourceRefAt(text, offset) else rClassRefAt(text, offset)) ?: return null
        // The index carries the precise declaring offset; a resource added/edited in an OPEN res buffer isn't
        // indexed until save, so fall back to the buffer-aware repository (declaring file only, offset 0) so
        // go-to-def still lands in the right file for an as-yet-unsaved declaration.
        return indexDefinition(type, name) ?: repoDefinition(module, type, name)
    }

    /** Buffer-aware go-to-target for an unindexed resource: the declaring file at offset 0 (the repository
     *  tracks the source file per resource, not the precise offset — only the index does). */
    private fun repoDefinition(module: Module, type: ResourceType, name: String): Pair<Path, Int>? =
        ctx.resourceRepo(module)?.definitions(type, name)?.firstNotNullOfOrNull { it.source }?.let { it to 0 }

    /** The local resource reference under [offset] in res XML (`@type/name`), as (type, R-field name). */
    private fun xmlResourceRefAt(text: String, offset: Int): Pair<ResourceType, String>? {
        val ref = ResourceReferences.scan(text).firstOrNull { offset in it.range } ?: return null
        val type = ref.type
        if (!ref.isLocal || ref.create || type == null) return null
        return type to sanitizeResName(ref.name)
    }

    /** An `R.type.name` access under [offset] in Java, as (type, name) — tolerant of where the caret is. */
    private fun rClassRefAt(text: String, offset: Int): Pair<ResourceType, String>? {
        val n = text.length
        if (offset < 0 || offset > n) return null
        fun part(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.' || c == '$'
        var s = offset.coerceIn(0, n)
        var e = offset.coerceIn(0, n)
        while (s > 0 && part(text[s - 1])) s--
        while (e < n && part(text[e])) e++
        val segs = text.substring(s, e).split('.').filter { it.isNotEmpty() }
        val ri = segs.indexOf("R")
        if (ri < 0 || ri + 2 >= segs.size) return null
        return (ResourceType.byRClass(segs[ri + 1]) ?: return null) to segs[ri + 2]
    }

    /** Resolve a resource to its declaration (file + offset) via the resource index, or null. */
    private fun indexDefinition(type: ResourceType, name: String): Pair<Path, Int>? =
        ctx.indexService.exact<ResourceDeclValue>(
            AndroidResourceIndex.id, AndroidResourceIndex.key(type.rClass, name)
        ).firstOrNull()?.let { Paths.get(it.filePath) to it.offset }

    /**
     * A render-ready model of the drawable XML in [file] ([text] is the live buffer), with every
     * `@color`/`@dimen`/`@drawable` reference resolved against the module's merged resources. Null for a
     * non-Android module or a file that isn't a drawable/color/mipmap resource.
     */
    fun drawablePreview(file: Path, text: String): DrawablePreview? {
        if (!DrawableXmlCatalog.appliesTo(file.toString())) return null
        val module = ctx.moduleForResourceFile(file) ?: return null
        if (module.facets.get(AndroidFacet.KEY) == null) return null
        return runCatching { DrawablePreviewParser.parse(text, drawableResolver(module)) }.getOrNull()
    }

    /** The `<color>` entries of a `res/values` XML [file] ([text] = live buffer), resolved to ARGB for swatches. */
    fun colorResources(file: Path, text: String): List<ColorEntry> {
        val resolver = ctx.moduleForResourceFile(file)?.let { drawableResolver(it) } ?: DrawableResolver.NONE
        return runCatching { ColorResources.parse(text, resolver) }.getOrDefault(emptyList())
    }

    /** Raw bytes of a resource file (for bitmap preview); null if unreadable. */
    fun resourceBytes(file: Path): ByteArray? = runCatching { Files.readAllBytes(file) }.getOrNull()

    private fun drawableResolver(module: Module): DrawableResolver {
        val repo = ctx.resourceRepo(module) ?: return DrawableResolver.NONE
        return object : DrawableResolver {
            override fun resolveColor(ref: String): Long? = resolveColorRef(ref, repo, 0)

            override fun resolveDimenDp(ref: String): Float? {
                val name = sanitizeResName(ref.substringAfterLast('/'))
                val v = repo.definitions(ResourceType.DIMEN, name).firstOrNull()?.value ?: return null
                return DIMEN_LITERAL.find(v)?.groupValues?.get(1)?.toFloatOrNull()
            }

            override fun resolveDrawable(ref: String): ResolvedDrawable? {
                val name = sanitizeResName(ref.substringAfterLast('/'))
                // A @color used where a drawable is expected resolves to a flat fill — let the color path handle it.
                if (ref.contains("color") && repo.has(ResourceType.COLOR, name)) return null
                val item = repo.definitions(ResourceType.DRAWABLE, name).firstOrNull()
                    ?: repo.definitions(ResourceType.MIPMAP, name).firstOrNull() ?: return null
                val src = item.source ?: return null
                val p = src.toString()
                return if (p.endsWith(".xml")) {
                    runCatching { src.readText() }.getOrNull()?.let { ResolvedDrawable.Xml(it) }
                } else {
                    ResolvedDrawable.BitmapFile(item.type.rClass, name, p)
                }
            }
        }
    }

    /** Resolve `@color/x` (transitively through `@color` indirection) to ARGB; `@android:color/x` via the table. */
    private fun resolveColorRef(ref: String, repo: ResourceRepository, depth: Int): Long? {
        if (depth > 8) return null
        val raw = ref.trim()
        if (raw.startsWith("#")) return AndroidColor.parseHex(raw)
        if (raw.contains("android:")) return AndroidColor.framework(raw.substringAfterLast('/'))
        if (!raw.startsWith("@")) return null
        val name = sanitizeResName(raw.substringAfterLast('/'))
        val v = repo.definitions(ResourceType.COLOR, name).firstOrNull()?.value ?: return null
        return when {
            v.startsWith("#") -> AndroidColor.parseHex(v)
            v.startsWith("@") -> resolveColorRef(v, repo, depth + 1)
            else -> null
        }
    }

    private fun sanitizeResName(s: String): String = s.replace('.', '_').replace('-', '_').trim()

    private val DIMEN_LITERAL = Regex("""(-?\d+(?:\.\d+)?)""")

    // ---- ModuleResources: any module type -------------------------------------------------------------
    //
    // Phrased in ContentRole, so nothing below is Android's. What a plugin cannot do without it is the
    // PUBLISH: writing a file is `java.nio`, and getting the build's staleness check, the indexes, the
    // synthetic classes and the editor's resolution to see it is the workspace event these emit.

    override fun resourceRoots(module: Module, role: ContentRole): List<Path> =
        module.contentRootsFor(role)

    override fun putResourceFile(
        module: Module, role: ContentRole, relativePath: String, content: String,
        onConflict: ResourceConflict,
    ): ResourceWrite = putResourceBytes(module, role, relativePath, onConflict) { content.toByteArray() }

    override fun putResourceFile(
        module: Module, role: ContentRole, relativePath: String, content: ByteArray,
        onConflict: ResourceConflict,
    ): ResourceWrite = putResourceBytes(module, role, relativePath, onConflict) { content }

    /**
     * The module-type-neutral write both [putResourceFile] overloads run. Knows nothing about `res/`: it
     * resolves a root of [role], refuses a path that leaves it, applies the conflict policy to the FILE, and
     * publishes so the build and the index see what it wrote.
     *
     * [bytes] is a lambda so the content is only materialized once the write is actually going to happen.
     */
    private fun putResourceBytes(
        module: Module, role: ContentRole, relativePath: String, onConflict: ResourceConflict,
        bytes: () -> ByteArray,
    ): ResourceWrite {
        if (relativePath.isBlank()) return ResourceWrite.Failed("relative path is blank")
        val root = resourceRoots(module, role).firstOrNull()?.toAbsolutePath()?.normalize()
            ?: return ResourceWrite.NoResourceRoot
        val requested = runCatching { root.resolve(relativePath).normalize() }.getOrNull()
            ?: return ResourceWrite.Failed("'$relativePath' is not a path")
        // A `..` segment would let a caller write anywhere on the device through an API that says "into this
        // module", so containment is checked after normalizing rather than by scanning the string.
        if (!requested.startsWith(root) || requested == root) {
            return ResourceWrite.Failed("'$relativePath' does not stay inside $root")
        }
        val existed = Files.exists(requested)
        val target = when {
            !existed -> requested
            onConflict == ResourceConflict.FAIL -> return ResourceWrite.AlreadyExists(requested)
            onConflict == ResourceConflict.REPLACE -> requested
            else -> freeSibling(requested)
        }
        return runCatching {
            Files.createDirectories(target.parent)
            Files.write(target, bytes())
            if (target != requested || !existed) ctx.events.fileCreated(target)
            else ctx.events.fileChanged(target)
            ResourceWrite.Written(target.fileName.toString(), target)
        }.getOrElse { ResourceWrite.Failed("could not write $target: ${it.message}") }
    }

    /** `report.json` -> the first free `report_1.json`, `report_2.json`, …: the suffix goes on the base name
     *  so the extension, which decides how the file is read, survives a rename. */
    private fun freeSibling(file: Path): Path {
        val name = file.fileName.toString()
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        return generateSequence(1) { it + 1 }
            .map { file.resolveSibling("${base}_$it$ext") }.first { !Files.exists(it) }
    }

    // ---- ModuleResources: Android's resource model ----------------------------------------------------
    //
    // Every read goes through the module's merged, fingerprint-cached repository, which is also what the
    // synthetic `R`, the layout preview and `@type/name` resolution read. That is deliberate: it is
    // buffer-aware, so a `<string>` typed into an open `res/` file is visible before it is saved, and a
    // plugin therefore sees the same resource set the editor is resolving against. The resource INDEX is
    // the other candidate and is the wrong one here: it carries declaring offsets these queries do not need,
    // and it lags an unsaved buffer. A module with no Android resources has no repository, so every one of
    // them answers empty rather than throwing.

    override fun types(module: Module): Set<String> =
        ctx.resourceRepo(module)?.types()?.mapTo(LinkedHashSet()) { it.rClass } ?: emptySet()

    override fun names(module: Module, rClass: String): List<String> {
        val type = ResourceType.byRClass(rClass) ?: return emptyList()
        return ctx.resourceRepo(module)?.names(type)?.toList() ?: emptyList()
    }

    override fun has(module: Module, rClass: String, name: String): Boolean {
        val type = ResourceType.byRClass(rClass) ?: return false
        return ctx.resourceRepo(module)?.has(type, name) == true
    }

    override fun find(module: Module, filter: ResourceFilter): List<ResourceEntry> {
        if (filter.limit <= 0) return emptyList()
        val repo = ctx.resourceRepo(module) ?: return emptyList()
        // Resolve the module's own roots ONCE per call rather than per item: `moduleOnly` is a path-prefix
        // test over every definition in a merged repository, which for an AndroidX-heavy module is thousands.
        val ownRoots =
            if (filter.moduleOnly) resourceRoots(module, ContentRole.ANDROID_RES)
                .map { it.toAbsolutePath().normalize() }
            else emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<ResourceEntry>()
        for (item in repo.all()) {
            if (out.size >= filter.limit) break
            if (!item.matches(filter, ownRoots)) continue
            if (!filter.allConfigs && !seen.add("${item.type.rClass}/${item.name}")) continue
            out += ResourceEntry(item.type.rClass, item.name, item.value, item.source, item.qualifier)
        }
        return out
    }

    private fun ResourceItem.matches(filter: ResourceFilter, ownRoots: List<Path>): Boolean {
        // Bound to locals rather than read through `filter`: a cross-module public property cannot be
        // smart-cast, so `filter.namePrefix` stays `String?` at the call below however it was just checked.
        val prefix = filter.namePrefix
        val contains = filter.nameContains
        if (filter.rClass != null && type.rClass != filter.rClass) return false
        if (filter.name != null && name != filter.name) return false
        if (prefix != null && !name.startsWith(prefix)) return false
        if (contains != null && !name.contains(contains, ignoreCase = true)) return false
        if (filter.qualifier != null && qualifier != filter.qualifier) return false
        if (filter.moduleOnly) {
            // No source is no proof of ownership, so a definition the parser could not attribute is dropped
            // rather than assumed local: `moduleOnly` is what a write consults, and a false positive there
            // would refuse a legitimate resource.
            val src = source?.toAbsolutePath()?.normalize() ?: return false
            if (ownRoots.none { src.startsWith(it) }) return false
        }
        return true
    }

    /** Where an authored Android resource goes: the module's first `res/` root, `main`'s ahead of a
     *  variant's. Null when the module declares none, which every non-Android module does. */
    private fun androidResRoot(module: Module): Path? =
        resourceRoots(module, ContentRole.ANDROID_RES).firstOrNull()

    override fun isValueType(rClass: String): Boolean =
        ResourceType.byRClass(rClass)?.let { it in VALUE_TYPES } == true

    override fun isFileType(rClass: String): Boolean =
        ResourceType.byRClass(rClass)?.let { it in FILE_TYPES } == true

    override fun putValueResource(
        module: Module, rClass: String, name: String, value: String, onConflict: ResourceConflict,
    ): ResourceWrite {
        val type = ResourceType.byRClass(rClass)
            ?: return ResourceWrite.Failed("unknown resource type '$rClass'")
        if (type !in VALUE_TYPES) {
            return ResourceWrite.Failed("@$rClass is not a value resource type; see createResourceFile")
        }
        if (name.isBlank()) return ResourceWrite.Failed("resource name is blank")
        val valuesDir = androidResRoot(module)?.resolve("values")
            ?: return ResourceWrite.NoResourceRoot
        val target = valuesDir.resolve(valuesFileName(type))
        val existingText = runCatching { if (Files.exists(target)) target.readText() else null }.getOrNull()

        // "Already declared" is judged against what the module declares ITSELF in the DEFAULT config, which is
        // where this writes: shadowing a library's `@string/app_name` stays allowed, adding the default a
        // `values-night/` override is missing stays allowed, and redeclaring your own does not. The target
        // file's own text is consulted as well, because the repository is a parse of the last content state
        // and a caller writing twice in a row would otherwise not see its first write until the fingerprint
        // turns over.
        fun declaredAt(candidate: String): Path? =
            find(
                module,
                ResourceFilter(
                    rClass = rClass, name = candidate, qualifier = "", moduleOnly = true,
                    allConfigs = true, limit = 1,
                ),
            ).firstOrNull()?.file
                ?: target.takeIf { existingText != null && existingText.declares(type, candidate) }

        val declared = declaredAt(name)
        val writeName = when {
            declared == null -> name
            onConflict == ResourceConflict.FAIL -> return ResourceWrite.AlreadyExists(declared)
            onConflict == ResourceConflict.REPLACE -> return replaceValueResource(type, name, value, declared)
            else -> generateSequence(1) { it + 1 }.map { "${name}_$it" }.first { declaredAt(it) == null }
        }

        val entry = "    <${type.rClass} name=\"$writeName\">${escapeXml(value)}</${type.rClass}>\n"
        return runCatching {
            Files.createDirectories(valuesDir)
            if (existingText == null) {
                target.writeText(RESOURCES_HEAD + entry + "</resources>\n")
            } else {
                val close = existingText.lastIndexOf("</resources>")
                target.writeText(
                    if (close >= 0) {
                        existingText.substring(0, close) + entry + existingText.substring(close)
                    } else {
                        existingText + entry
                    }
                )
            }
            // Reaction (res .xml): refresh the synthetic R + re-index the file, so the new resource resolves
            // without a rebuild. Publishing is what an out-of-process consumer sees too.
            if (existingText == null) ctx.events.fileCreated(target) else ctx.events.fileChanged(target)
            ResourceWrite.Written(writeName, target)
        }.getOrElse { ResourceWrite.Failed("could not write $target: ${it.message}") }
    }

    /**
     * Rewrite an existing `<type name="…">old</type>` body in the file that declares it. Only the paired
     * element form is rewritten; a declaration written some other way (a self-closing `<item>`, a value split
     * across an `<item type=…>`) is reported rather than appended beside, since a second declaration of the
     * same name in one module is an aapt2 error rather than an update.
     */
    private fun replaceValueResource(
        type: ResourceType, name: String, value: String, file: Path,
    ): ResourceWrite {
        val text = runCatching { file.readText() }.getOrNull()
            ?: return ResourceWrite.Failed("could not read $file")
        val element = Regex(
            """(<${type.rClass}\b[^>]*\bname\s*=\s*"${Regex.escape(name)}"[^>]*>)(.*?)(</${type.rClass}\s*>)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val match = element.find(text)
            ?: return ResourceWrite.Failed("@${type.rClass}/$name in $file is not a simple element to replace")
        val replaced = text.substring(0, match.range.first) +
            match.groupValues[1] + escapeXml(value) + match.groupValues[3] +
            text.substring(match.range.last + 1)
        return runCatching {
            file.writeText(replaced)
            ctx.events.fileChanged(file)
            ResourceWrite.Written(name, file)
        }.getOrElse { ResourceWrite.Failed("could not write $file: ${it.message}") }
    }

    override fun createResourceFile(
        module: Module, rClass: String, name: String, content: String?, onConflict: ResourceConflict,
    ): ResourceWrite {
        val type = ResourceType.byRClass(rClass)
            ?: return ResourceWrite.Failed("unknown resource type '$rClass'")
        if (type !in FILE_TYPES) {
            return ResourceWrite.Failed("@$rClass is not a file resource type; see putValueResource")
        }
        if (name.isBlank()) return ResourceWrite.Failed("resource name is blank")
        val folder = androidResRoot(module)?.resolve(type.rClass)
            ?: return ResourceWrite.NoResourceRoot
        // Judged against disk, not the repository: this creates a FILE, and a name free in the model but taken
        // on disk would silently clobber it.
        val requested = folder.resolve("$name.xml")
        val existed = Files.exists(requested)
        val target = when {
            !existed -> requested
            onConflict == ResourceConflict.FAIL -> return ResourceWrite.AlreadyExists(requested)
            onConflict == ResourceConflict.REPLACE -> requested
            else -> generateSequence(1) { it + 1 }
                .map { folder.resolve("${name}_$it.xml") }.first { !Files.exists(it) }
        }
        return runCatching {
            Files.createDirectories(folder)
            target.writeText(content ?: resourceFileStub(type))
            val created = target != requested || !existed
            if (created) ctx.events.fileCreated(target) else ctx.events.fileChanged(target)
            ResourceWrite.Written(target.fileName.toString().removeSuffix(".xml"), target)
        }.getOrElse { ResourceWrite.Failed("could not write $target: ${it.message}") }
    }

    /** Whether [this] declares `<type name="[name]">` (the file-local half of the conflict check). */
    private fun String.declares(type: ResourceType, name: String): Boolean =
        Regex("""<${type.rClass}\b[^>]*\bname\s*=\s*"${Regex.escape(name)}"""").containsMatchIn(this)

    /** The `res/values` file a type conventionally lives in, matching what the templates scaffold. */
    private fun valuesFileName(type: ResourceType): String = when (type) {
        ResourceType.STRING -> "strings.xml"
        ResourceType.COLOR -> "colors.xml"
        ResourceType.DIMEN -> "dimens.xml"
        else -> "values.xml"
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** A minimal, valid starting document for a newly created file resource of [type]. */
    private fun resourceFileStub(type: ResourceType): String {
        val ns = "http://schemas.android.com/apk/res/android"
        return XML_HEAD + when (type) {
            ResourceType.LAYOUT -> "<FrameLayout xmlns:android=\"$ns\"\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\">\n\n</FrameLayout>\n"

            ResourceType.DRAWABLE -> "<shape xmlns:android=\"$ns\" android:shape=\"rectangle\">\n    <solid android:color=\"#FF000000\" />\n</shape>\n"

            ResourceType.MENU -> "<menu xmlns:android=\"$ns\" xmlns:app=\"http://schemas.android.com/apk/res-auto\">\n\n</menu>\n"

            ResourceType.ANIM, ResourceType.ANIMATOR -> "<set xmlns:android=\"$ns\">\n\n</set>\n"

            else -> "<resources>\n\n</resources>\n"
        }
    }

    private companion object {
        const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        const val RESOURCES_HEAD = XML_HEAD + "<resources>\n"

        /** Types written as an entry in `res/values/…`. */
        val VALUE_TYPES = setOf(
            ResourceType.STRING,
            ResourceType.COLOR,
            ResourceType.DIMEN,
            ResourceType.BOOL,
            ResourceType.INTEGER,
            ResourceType.ID,
        )

        /** Types written as a standalone file under `res/<type>/`. */
        val FILE_TYPES = setOf(
            ResourceType.LAYOUT,
            ResourceType.DRAWABLE,
            ResourceType.MENU,
            ResourceType.ANIM,
            ResourceType.ANIMATOR,
        )
    }
}
