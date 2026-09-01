package dev.ide.core.services

import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.icons.AppIconFile
import dev.ide.android.support.icons.AppIconGenerator
import dev.ide.android.support.icons.AppIconLayer
import dev.ide.android.support.icons.AppIconPlan
import dev.ide.android.support.icons.AppIconSpec
import dev.ide.android.support.icons.BundledMaterialIcons
import dev.ide.android.support.icons.ICON_REPOSITORY_EP
import dev.ide.android.support.icons.IconArtwork
import dev.ide.android.support.icons.IconEntry
import dev.ide.android.support.icons.IconRepository
import dev.ide.android.support.icons.IconSearch
import dev.ide.android.support.icons.IconStyle
import dev.ide.android.support.icons.IconVariant
import dev.ide.android.support.icons.ManifestIconWriter
import dev.ide.android.support.icons.MaterialSymbolsRemote
import dev.ide.android.support.icons.SvgConvertOptions
import dev.ide.android.support.icons.SvgToVectorDrawable
import dev.ide.android.support.icons.VectorDrawableWriter
import dev.ide.android.support.icons.recolored
import dev.ide.android.support.icons.resized
import dev.ide.android.support.preview.DrawablePreview
import dev.ide.android.support.preview.DrawablePreviewParser
import dev.ide.android.support.preview.DrawableResolver
import dev.ide.android.support.preview.ResourceDrawableResolver
import dev.ide.android.support.preview.VectorSpec
import dev.ide.android.support.resources.AndroidLauncherIcon
import dev.ide.android.support.resources.AndroidManifestParser
import dev.ide.android.support.resources.LauncherIcon
import dev.ide.android.support.resources.ResourceModel
import dev.ide.android.support.resources.ResourceRepository
import dev.ide.android.support.resources.ResourceType
import dev.ide.core.EngineContext
import dev.ide.model.ContentRole
import dev.ide.model.Module
import dev.ide.platform.PluginId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * WORKSPACE-scoped icon service: the browsable icon repositories, the catalogue of drawables a module already
 * has, and the writer that turns a picked icon into a `res/drawable` file.
 *
 * Repositories come from [ICON_REPOSITORY_EP], with the two Material ones registered here as built-ins, so a
 * plugin's icon library appears in the picker with no change to the picker. Everything is blocking; the
 * backend calls it off the UI thread.
 */
internal class IconManagerService(private val ctx: EngineContext) {

    /** A `res/` directory an asset can be written into, with the module and source set that own it. */
    data class Target(
        val module: Module,
        val sourceSetName: String,
        val resDir: Path,
        val isDefault: Boolean,
    )

    /** A drawable/mipmap resource the project already declares, across every config it is declared in. */
    data class CatalogEntry(
        val moduleName: String,
        val resType: ResourceType,
        val name: String,
        val configurations: List<CatalogConfig>,
    )

    data class CatalogConfig(val qualifier: String, val path: Path, val isRaster: Boolean)

    // --- repositories --------------------------------------------------------------------------------

    private val repositories: List<IconRepository> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        // Built-ins register themselves the first time anything asks, the same way the other engines seed
        // their extension points. A host or plugin that registered its own is already in the list.
        val registry = ctx.platform.extensions
        val existing = registry.extensions(ICON_REPOSITORY_EP)
        if (existing.none { it.id == BundledMaterialIcons.ID }) {
            registry.register(ICON_REPOSITORY_EP, BundledMaterialIcons(), BUILT_IN)
        }
        if (existing.none { it.id == MaterialSymbolsRemote.ID }) {
            registry.register(ICON_REPOSITORY_EP, MaterialSymbolsRemote(iconCacheDir()), BUILT_IN)
        }
        registry.extensions(ICON_REPOSITORY_EP)
    }

    fun repositories(): List<IconRepository> = repositories

    fun repository(id: String): IconRepository? = repositories.firstOrNull { it.id == id }

    fun loadRepository(id: String): Result<Int> {
        val repo = repository(id) ?: return Result.failure(IllegalArgumentException("No icon repository '$id'"))
        return repo.load().map { repo.entries().size }
    }

    fun searchIcons(repoId: String, query: String, limit: Int): List<IconEntry> {
        val repo = repository(repoId) ?: return emptyList()
        return IconSearch.filter(repo.entries(), query, limit)
    }

    fun artwork(repoId: String, name: String, variant: IconVariant): IconArtwork? {
        val repo = repository(repoId) ?: return null
        val entry = repo.entries().firstOrNull { it.name == name } ?: return null
        return repo.artwork(entry, variant)
    }

    /** Where downloaded icon catalogues and SVGs live: app-wide when the host shares a cache root. */
    private fun iconCacheDir(): Path =
        (ctx.sharedCachesRoot ?: ctx.workspaceRoot.resolve(".platform/caches")).resolve("icons")

    // --- the project's own resources -----------------------------------------------------------------

    /** Every Android module in the workspace, application modules first (the ones an icon usually goes to). */
    fun androidModules(): List<Module> = ctx.modules()
        .filter { it.facets.get(AndroidFacet.KEY) != null }
        .sortedByDescending { it.facets.get(AndroidFacet.KEY)?.isApplication == true }

    /**
     * The `res/` directories an asset can be written to. The application module's `main` is marked as the
     * default, since that is where an icon belongs unless the user says otherwise.
     */
    fun targets(): List<Target> {
        val modules = androidModules()
        val appModule = modules.firstOrNull { it.facets.get(AndroidFacet.KEY)?.isApplication == true }
        val out = ArrayList<Target>()
        for (module in modules) {
            val moduleDir = ctx.moduleRoot(module) ?: continue
            for (sourceSet in module.sourceSets) {
                for (root in sourceSet.contentRoots) {
                    if (ContentRole.ANDROID_RES !in root.roles) continue
                    val dir = resolveRoot(moduleDir, root.dir.path)
                    out += Target(
                        module = module,
                        sourceSetName = sourceSet.name,
                        resDir = dir,
                        isDefault = module.id == appModule?.id && sourceSet.name == MAIN_SOURCE_SET,
                    )
                }
            }
        }
        // The default first, then a stable order so the picker's list doesn't shuffle between openings.
        return out.sortedWith(compareByDescending<Target> { it.isDefault }.thenBy { it.module.name }.thenBy { it.sourceSetName })
    }

    /**
     * Drawable and mipmap resources declared by [module]'s own source sets. Deliberately not the merged
     * repository: an app depending on material3 inherits thousands of library drawables, and a catalogue of
     * "icons in this project" that lists all of them is useless.
     */
    fun catalog(module: Module): List<CatalogEntry> {
        val dirs = ownResourceDirs(module)
        if (dirs.isEmpty()) return emptyList()
        val repo = runCatching { ResourceModel.DEFAULT.parse(dirs) }.getOrNull() ?: return emptyList()
        val out = ArrayList<CatalogEntry>()
        for (type in listOf(ResourceType.DRAWABLE, ResourceType.MIPMAP)) {
            for (name in repo.names(type)) {
                val configs = repo.definitions(type, name).mapNotNull { item ->
                    val source = item.source ?: return@mapNotNull null
                    CatalogConfig(item.qualifier, source, isRaster(source))
                }
                if (configs.isNotEmpty()) out += CatalogEntry(module.name, type, name, configs)
            }
        }
        return out.sortedWith(compareBy({ it.resType.ordinal }, { it.name }))
    }

    /** The catalogue for [moduleName], or every Android module's when null. */
    fun catalog(moduleName: String?): List<CatalogEntry> {
        val modules = androidModules()
        val selected = if (moduleName == null) modules else modules.filter { it.name == moduleName }
        return selected.flatMap { catalog(it) }
    }

    /** The render-ready model of the resource file at [path], resolving its references against its module. */
    fun resourceArtwork(path: Path): DrawablePreview? {
        if (!path.isRegularFile()) return null
        if (isRaster(path)) return null
        val text = runCatching { ctx.overlayText(path) ?: path.readText() }.getOrNull() ?: return null
        return DrawablePreviewParser.parse(text, resolverFor(path))
    }

    /** Raw bytes of a raster resource, for the grid's bitmap tiles. */
    fun resourceBytes(path: Path): ByteArray? =
        runCatching { path.takeIf { it.isRegularFile() }?.readBytes() }.getOrNull()

    /** A `@color`/`@dimen`/`@drawable` resolver backed by the module that owns [path]. */
    private fun resolverFor(path: Path): DrawableResolver {
        val module = ctx.moduleForResourceFile(path) ?: return DrawableResolver.NONE
        val repo: ResourceRepository = ctx.resourceRepo(module) ?: return DrawableResolver.NONE
        return ResourceDrawableResolver.of(repo)
    }

    // --- writing -------------------------------------------------------------------------------------

    /** The existing file backing `<resType>/<name>` under [target], or null when the name is free. */
    fun existingResource(target: Target, resType: ResourceType, name: String): Path? {
        val res = target.resDir
        if (!res.exists()) return null
        val prefix = resType.rClass
        return runCatching {
            Files.list(res).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .filter { dir -> dir.name == prefix || dir.name.startsWith("$prefix-") }
                    .map { dir -> findNamed(dir, name) }
                    .filter { it != null }
                    .findFirst()
                    .orElse(null)
            }
        }.getOrNull()
    }

    private fun findNamed(dir: Path, name: String): Path? = runCatching {
        Files.list(dir).use { stream ->
            stream.filter { it.isRegularFile() }
                .filter { it.name.substringBeforeLast('.') == name }
                .findFirst()
                .orElse(null)
        }
    }.getOrNull()

    /**
     * Writes [content] to `<resDir>/<resType>/<name>.<extension>`, replacing an existing declaration of the
     * same name (in any configuration) only when [overwrite] is set. Returns the written path, or the
     * conflicting path when one exists and overwriting was not asked for.
     */
    fun write(
        target: Target,
        resType: ResourceType,
        name: String,
        extension: String,
        bytes: ByteArray,
        overwrite: Boolean,
    ): WriteOutcome {
        val invalid = validateResourceName(name)
        if (invalid != null) return WriteOutcome.Invalid(invalid)

        val existing = existingResource(target, resType, name)
        if (existing != null && !overwrite) return WriteOutcome.Conflict(existing)

        val file = target.resDir.resolve(resType.rClass).resolve("$name.$extension")
        return runCatching {
            file.createParentDirectories()
            // Replacing a resource declared with a different extension (an XML icon over a PNG one, say) has
            // to delete the old file, or aapt sees two declarations of the same name.
            if (existing != null && existing != file) Files.deleteIfExists(existing)
            Files.write(file, bytes)
            WriteOutcome.Written(file)
        }.getOrElse { WriteOutcome.Failed(it.message ?: it::class.simpleName ?: "Write failed") }
    }

    /** The result of a write: the caller maps this onto the UI DTO. */
    sealed interface WriteOutcome {
        data class Written(val path: Path) : WriteOutcome
        data class Conflict(val existing: Path) : WriteOutcome
        data class Invalid(val reason: String) : WriteOutcome
        data class Failed(val reason: String) : WriteOutcome
    }

    /** The VectorDrawable XML for [artwork], resized and optionally repainted. */
    fun vectorXml(artwork: IconArtwork, sizeDp: Float, colorArgb: Long?): String {
        var spec = artwork.spec.resized(sizeDp, sizeDp)
        if (colorArgb != null) spec = spec.recolored(colorArgb)
        return VectorDrawableWriter.write(spec)
    }

    /** Convert [svgText] for preview or import; null when it isn't an SVG document. */
    fun convertSvg(svgText: String, sizeDp: Float?, colorArgb: Long?): IconArtwork? {
        val converted = SvgToVectorDrawable.toSpec(
            svgText,
            SvgConvertOptions(widthDp = sizeDp, heightDp = sizeDp, overrideColor = colorArgb),
        ) ?: return null
        return IconArtwork(converted.spec, converted.warnings)
    }

    /**
     * The Android namespace of the module owning [file], which is the package its generated `R` class lives
     * in. Null for a file outside any Android module, where there is no `R` to import.
     */
    fun namespaceForFile(file: Path): String? {
        val module = ctx.moduleForEditableFile(file) ?: ctx.moduleForFile(file) ?: return null
        return module.facets.get(AndroidFacet.KEY)?.namespace?.takeIf { it.isNotEmpty() }
    }

    // --- Compose icons -------------------------------------------------------------------------------

    /** The `Icons.*` properties [moduleName] can reference, from its classpath. */
    fun composeIcons(moduleName: String?): List<ComposeIconIndex.Entry> {
        val module = moduleForAny(moduleName) ?: return emptyList()
        return ComposeIconIndex.scan(module)
    }

    /**
     * A Compose icon's artwork.
     *
     * `androidx.compose.material.icons` ships Google's Material icons, which is the same artwork the Material
     * repositories serve, so the geometry is resolved by name from those: the bundled subset first (offline,
     * instant) and then any other repository that has it. What this deliberately does NOT do is evaluate the
     * library's own `ImageVector`, which would be needed to render a *custom* icon library faithfully. Doing
     * that means running a library property getter through the Compose interpreter, and the interpreter is
     * reached from here only through the injected preview-runner port; see the Icon Manager notes.
     */
    fun composeIconArtwork(property: String, variant: IconVariant): IconArtwork? {
        val name = ComposeIconIndex.repositoryName(property)
        for (repo in repositories) {
            val entry = repo.entries().firstOrNull { it.name == name } ?: continue
            repo.artwork(entry, variant)?.let { return it }
        }
        return null
    }

    /** The import a Compose icon reference needs, or null when the style is not on the classpath. */
    fun composeIconImport(property: String, style: String): String =
        ComposeIconIndex.importFor(property, style)

    /** Any module, Android or not: Compose icons are equally available to a plain Kotlin/JVM module. */
    private fun moduleForAny(moduleName: String?): Module? {
        val all = ctx.modules()
        return if (moduleName == null) {
            // Prefer a module that actually has the library, so a multi-module project lands somewhere useful.
            all.firstOrNull { ComposeIconIndex.available(it) } ?: all.firstOrNull()
        } else {
            all.firstOrNull { it.name == moduleName }
        }
    }

    // --- the app icon --------------------------------------------------------------------------------

    /** A module's current launcher icon: what the manifest points at, and the resolved artwork. */
    data class LauncherIconState(
        val module: Module,
        val iconRef: String?,
        val roundIconRef: String?,
        val drawable: DrawablePreview?,
        val rasterPath: Path?,
    )

    /** The launcher icon [moduleName] declares, or the application module's when null. */
    fun launcherIcon(moduleName: String?): LauncherIconState? {
        val module = moduleFor(moduleName) ?: return null
        val moduleDir = ctx.moduleRoot(module) ?: return null
        val facet = module.facets.get(AndroidFacet.KEY) ?: return null
        val manifest = AndroidManifestParser.parse(moduleDir.resolve(facet.manifest))
        val resolved = AndroidLauncherIcon.locate(ownResourceDirs(module), manifest?.appIcon, manifest?.appRoundIcon)
        return LauncherIconState(
            module = module,
            iconRef = manifest?.appIcon,
            roundIconRef = manifest?.appRoundIcon,
            drawable = (resolved as? LauncherIcon.Drawable)?.preview,
            rasterPath = (resolved as? LauncherIcon.Raster)?.path,
        )
    }

    /** The plan for [spec], against [target]'s `res/` directory. */
    fun planAppIcon(spec: AppIconSpec, target: Target): AppIconPlan =
        AppIconGenerator.plan(spec) { relative -> resolveOutput(target, relative)?.exists() == true }

    /**
     * Resolve one layer of a UI spec into a generator layer. Returns [AppIconLayer.None] when the source
     * cannot be resolved, so a missing icon degrades to a blank layer with a warning rather than a failure.
     */
    fun resolveLayer(source: LayerSource, warnings: MutableList<String>): AppIconLayer = when (source) {
        LayerSource.None -> AppIconLayer.None
        is LayerSource.Flat -> AppIconLayer.Color(source.argb)

        is LayerSource.Repo -> {
            val art = artwork(source.repoId, source.name, source.variant)
            if (art == null) {
                warnings += "Could not resolve the icon ${source.name}"
                AppIconLayer.None
            } else {
                warnings += art.warnings
                AppIconLayer.Vector(art.spec, source.scale, source.offsetX, source.offsetY, source.tintArgb)
            }
        }

        is LayerSource.Resource -> {
            val path = Paths.get(source.path)
            if (isRaster(path)) {
                val bytes = resourceBytes(path)
                if (bytes == null) {
                    warnings += "Could not read ${path.name}"
                    AppIconLayer.None
                } else {
                    AppIconLayer.Raster(bytes, path.extension.lowercase().ifEmpty { "png" })
                }
            } else {
                val spec = (resourceArtwork(path) as? DrawablePreview.Vector)?.spec
                if (spec == null) {
                    warnings += "${path.name} is not a vector, so it cannot be scaled into the icon box"
                    AppIconLayer.None
                } else {
                    AppIconLayer.Vector(spec, source.scale, source.offsetX, source.offsetY, source.tintArgb)
                }
            }
        }

        is LayerSource.ImageFile -> {
            val path = Paths.get(source.path)
            val bytes = runCatching { path.readBytes() }.getOrNull()
            if (bytes == null) {
                warnings += "Could not read ${path.name}"
                AppIconLayer.None
            } else {
                AppIconLayer.Raster(bytes, path.extension.lowercase().ifEmpty { "png" })
            }
        }
    }

    /** Where a layer's artwork comes from, mirroring the UI's own layer model. */
    sealed interface LayerSource {
        data object None : LayerSource
        data class Flat(val argb: Long) : LayerSource
        data class Repo(
            val repoId: String,
            val name: String,
            val variant: IconVariant,
            val scale: Float,
            val offsetX: Float,
            val offsetY: Float,
            val tintArgb: Long?,
        ) : LayerSource

        data class Resource(
            val path: String,
            val scale: Float,
            val offsetX: Float,
            val offsetY: Float,
            val tintArgb: Long?,
        ) : LayerSource

        data class ImageFile(val path: String) : LayerSource
    }

    /** The composed 108-unit layer for a resolved [layer], or null when there is nothing to draw. */
    fun composedLayer(layer: AppIconLayer): VectorSpec? = when (layer) {
        AppIconLayer.None -> null
        is AppIconLayer.Color -> null
        is AppIconLayer.Raster -> null
        is AppIconLayer.Vector -> {
            val composed = AppIconGenerator.composeLayer(layer.spec, layer.scale, layer.offsetX, layer.offsetY)
            layer.tintArgb?.let { composed.recolored(it) } ?: composed
        }
    }

    /**
     * Write [plan]'s files under [target] and point the manifest at the icon. [rasters] supplies the bytes
     * for each raster the host rendered; a raster with no bytes is skipped and reported.
     */
    fun applyAppIcon(plan: AppIconPlan, target: Target, rasters: Map<String, ByteArray>): ApplyOutcome {
        val written = ArrayList<Path>()
        val skipped = ArrayList<String>()
        for (file in plan.files) {
            val destination = resolveOutput(target, file.relativePath)
                ?: return ApplyOutcome.Failed("Refusing to write outside the module: ${file.relativePath}")
            val bytes = when (file) {
                is AppIconFile.Text -> file.content.encodeToByteArray()
                is AppIconFile.Bytes -> file.bytes
                is AppIconFile.Raster -> rasters[file.relativePath] ?: run {
                    skipped += file.relativePath
                    continue
                }
            }
            val result = runCatching {
                destination.createParentDirectories()
                Files.write(destination, bytes)
                Unit
            }
            if (result.isFailure) {
                return ApplyOutcome.Failed(result.exceptionOrNull()?.message ?: "Could not write $destination")
            }
            written.add(destination)
        }

        val manifestEdit = plan.manifest
        val manifestPath = manifestEdit?.let { manifestOf(target.module) }
        val manifestUpdated = if (manifestEdit == null || manifestPath == null) false else {
            val text = runCatching { manifestPath.readText() }.getOrNull()
            val patched = text?.let { ManifestIconWriter.apply(it, manifestEdit) }
            patched != null && runCatching { manifestPath.writeText(patched) }.isSuccess
        }
        return ApplyOutcome.Written(written, skipped, manifestUpdated)
    }

    sealed interface ApplyOutcome {
        data class Written(
            val files: List<Path>,
            val skippedRasters: List<String>,
            val manifestUpdated: Boolean,
        ) : ApplyOutcome

        data class Failed(val reason: String) : ApplyOutcome
    }

    /** The module named [moduleName], or the application module (then any Android module) when null. */
    fun moduleFor(moduleName: String?): Module? {
        val modules = androidModules()
        return if (moduleName == null) modules.firstOrNull() else modules.firstOrNull { it.name == moduleName }
    }

    /** The target whose module is [moduleName], preferring `main`. */
    fun targetFor(moduleName: String?): Target? {
        val candidates = targets()
        val module = moduleFor(moduleName) ?: return candidates.firstOrNull()
        return candidates.firstOrNull { it.module.id == module.id && it.sourceSetName == MAIN_SOURCE_SET }
            ?: candidates.firstOrNull { it.module.id == module.id }
    }

    private fun manifestOf(module: Module): Path? {
        val facet = module.facets.get(AndroidFacet.KEY) ?: return null
        val moduleDir = ctx.moduleRoot(module) ?: return null
        return moduleDir.resolve(facet.manifest).takeIf { it.exists() }
    }

    /**
     * A plan path resolved against [target]'s `res/` directory. Paths may step up one level (the Play Store
     * image lives in the source-set root beside `res/`), so the result is checked to still be inside the
     * module: a plan is data, and data should not be able to write anywhere on disk.
     */
    private fun resolveOutput(target: Target, relativePath: String): Path? {
        val moduleDir = ctx.moduleRoot(target.module)?.normalize() ?: return null
        val candidate = target.resDir.resolve(relativePath).normalize()
        return candidate.takeIf { it.startsWith(moduleDir) }
    }

    // --- helpers -------------------------------------------------------------------------------------

    /** [module]'s own `res/` roots (not its dependencies'), which is what the catalogue and targets mean. */
    private fun ownResourceDirs(module: Module): List<Path> {
        val moduleDir = ctx.moduleRoot(module) ?: return emptyList()
        return module.sourceSets
            .flatMap { it.contentRoots }
            .filter { ContentRole.ANDROID_RES in it.roles }
            .map { resolveRoot(moduleDir, it.dir.path) }
            .filter { it.exists() }
            .distinct()
    }

    /** A content root's path, which the model may store relative to the module. */
    private fun resolveRoot(moduleDir: Path, rootPath: String): Path {
        val p = Paths.get(rootPath)
        return if (p.isAbsolute) p else moduleDir.resolve(p)
    }

    private fun isRaster(path: Path): Boolean = path.extension.lowercase() != "xml"

    companion object {
        private val BUILT_IN = PluginId("android-support")
        private const val MAIN_SOURCE_SET = "main"

        /** Android resource file names are restricted to `[a-z0-9_.]` starting with a letter. */
        internal fun validateResourceName(name: String): String? = when {
            name.isBlank() -> "Enter a name"
            !name.first().isLetter() -> "A resource name must start with a letter"
            name.any { it.isUpperCase() } -> "A resource name must be lowercase"
            name.any { !it.isLowerCase() && !it.isDigit() && it != '_' } ->
                "Use only lowercase letters, digits and underscores"

            else -> null
        }

        /** `outlined`/`rounded`/`sharp` from the UI, defaulting to outlined for anything unrecognised. */
        internal fun styleOf(raw: String): IconStyle = when (raw.lowercase()) {
            "rounded" -> IconStyle.ROUNDED
            "sharp" -> IconStyle.SHARP
            else -> IconStyle.OUTLINED
        }
    }
}
