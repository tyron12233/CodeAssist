package dev.ide.core.backend

import dev.ide.android.support.icons.AppIconFile
import dev.ide.android.support.icons.AppIconLayer
import dev.ide.android.support.icons.AppIconSpec
import dev.ide.android.support.icons.IconArtwork
import dev.ide.android.support.icons.IconEntry
import dev.ide.android.support.icons.IconVariant
import dev.ide.android.support.preview.DrawablePreview
import dev.ide.android.support.resources.ResourceType
import dev.ide.core.BackendContext
import dev.ide.core.services.ComposeIconIndex
import dev.ide.core.services.IconManagerService
import dev.ide.ui.backend.IconService
import dev.ide.ui.backend.UiAppIconPlan
import dev.ide.ui.backend.UiAppIconPreview
import dev.ide.ui.backend.UiAppIconRaster
import dev.ide.ui.backend.UiAppIconSpec
import dev.ide.ui.backend.UiAppIconState
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.backend.IconSnippets
import dev.ide.ui.backend.UiIconArtwork
import dev.ide.ui.backend.UiIconLayer
import dev.ide.ui.backend.UiIconRef
import dev.ide.ui.backend.UiInsertionTarget
import dev.ide.ui.backend.UiIconEntry
import dev.ide.ui.backend.UiIconImport
import dev.ide.ui.backend.UiIconImportResult
import dev.ide.ui.backend.UiIconLoadResult
import dev.ide.ui.backend.UiIconRepo
import dev.ide.ui.backend.UiIconTarget
import dev.ide.ui.backend.UiIconVariant
import dev.ide.ui.backend.UiRasterFile
import dev.ide.ui.backend.UiResourceConfig
import dev.ide.ui.backend.UiResourceIcon
import dev.ide.ui.backend.UiTextEdit
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * [IconService]: the Icon Manager's backend. Maps the engine's icon service onto the neutral UI DTOs, and
 * keeps every filesystem and network touch on a background dispatcher.
 */
internal class IconBackend(private val ctx: BackendContext) : IconService {

    private val icons: IconManagerService? get() = ctx.servicesOrNull?.icons

    // --- repositories --------------------------------------------------------------------------------

    override fun iconRepositories(): List<UiIconRepo> = icons?.repositories()?.map { repo ->
        val count = repo.entries().size
        UiIconRepo(
            id = repo.id,
            displayName = repo.displayName,
            license = repo.license,
            attribution = repo.attribution,
            requiresNetwork = repo.requiresNetwork,
            loaded = count > 0,
            iconCount = count,
        )
    }.orEmpty()

    override suspend fun loadRepository(repoId: String): UiIconLoadResult {
        val service = icons ?: return UiIconLoadResult(ok = false, message = "No project is open")
        return ctx.background("icons.load") {
            service.loadRepository(repoId).fold(
                onSuccess = { UiIconLoadResult(ok = true, iconCount = it) },
                onFailure = { UiIconLoadResult(ok = false, message = it.message ?: "Could not load the icon set") },
            )
        }
    }

    override suspend fun searchIcons(repoId: String, query: String, limit: Int): List<UiIconEntry> {
        val service = icons ?: return emptyList()
        return ctx.background("icons.search") {
            service.searchIcons(repoId, query, limit).map(::toUiEntry)
        }
    }

    override suspend fun iconArtwork(repoId: String, name: String, variant: UiIconVariant): UiIconArtwork? {
        val service = icons ?: return null
        return ctx.background("icons.artwork") {
            service.artwork(repoId, name, variant.toEngine())?.let {
                UiIconArtwork(DrawableMapping.toUi(DrawablePreview.Vector(it.spec)), it.warnings)
            }
        }
    }

    // --- the project's own resources -----------------------------------------------------------------

    override suspend fun projectIcons(moduleName: String?): List<UiResourceIcon> {
        val service = icons ?: return emptyList()
        return ctx.background("icons.catalog") {
            service.catalog(moduleName).map { entry ->
                UiResourceIcon(
                    moduleName = entry.moduleName,
                    resType = entry.resType.rClass,
                    name = entry.name,
                    configurations = entry.configurations.map {
                        UiResourceConfig(it.qualifier, it.path.toString(), it.isRaster)
                    },
                )
            }
        }
    }

    override suspend fun resourceArtwork(path: String): UiIconArtwork? {
        val service = icons ?: return null
        return ctx.background("icons.resourceArtwork") {
            service.resourceArtwork(Paths.get(path))?.let { UiIconArtwork(DrawableMapping.toUi(it)) }
        }
    }

    override suspend fun resourceBytes(path: String): ByteArray? {
        val service = icons ?: return null
        return ctx.background("icons.resourceBytes") { service.resourceBytes(Paths.get(path)) }
    }

    // --- importing -----------------------------------------------------------------------------------

    override fun importTargets(): List<UiIconTarget> = icons?.targets()?.map { it.toUi() }.orEmpty()

    override fun existingResource(target: UiIconTarget, resType: String, name: String): String? {
        val service = icons ?: return null
        val engineTarget = service.targets().firstOrNull { it.matches(target) } ?: return null
        return service.existingResource(engineTarget, resTypeOf(resType), name)?.toString()
    }

    override suspend fun importIcon(
        repoId: String,
        name: String,
        variant: UiIconVariant,
        request: UiIconImport,
    ): UiIconImportResult {
        val service = icons ?: return noProject()
        return ctx.background("icons.import") {
            val artwork = service.artwork(repoId, name, variant.toEngine())
                ?: return@background UiIconImportResult(ok = false, message = "Could not resolve $name")
            val xml = service.vectorXml(artwork, request.sizeDp, request.colorArgb)
            writeText(service, request, xml, artwork.warnings)
        }
    }

    override suspend fun importSvg(svgText: String, request: UiIconImport): UiIconImportResult {
        val service = icons ?: return noProject()
        return ctx.background("icons.importSvg") {
            val artwork = service.convertSvg(svgText, request.sizeDp, request.colorArgb)
                ?: return@background UiIconImportResult(ok = false, message = "That file is not an SVG image")
            writeText(service, request, service.vectorXml(artwork, request.sizeDp, request.colorArgb), artwork.warnings)
        }
    }

    override suspend fun importRaster(bytes: ByteArray, extension: String, request: UiIconImport): UiIconImportResult {
        val service = icons ?: return noProject()
        val ext = extension.removePrefix(".").lowercase()
        if (ext !in RASTER_EXTENSIONS) {
            return UiIconImportResult(ok = false, message = "$ext files can't be used as a drawable resource")
        }
        return ctx.background("icons.importRaster") {
            finish(service.write(target(service, request) ?: return@background noTarget(),
                resTypeOf(request.resType), request.name, ext, bytes, request.overwrite))
        }
    }

    override suspend fun copyResource(sourcePath: String, request: UiIconImport): UiIconImportResult {
        val service = icons ?: return noProject()
        return ctx.background("icons.copyResource") {
            val source = Paths.get(sourcePath)
            val extension = source.fileName?.toString()?.substringAfterLast('.', "")?.lowercase().orEmpty()
            val target = target(service, request) ?: return@background noTarget()
            if (extension == "xml") {
                // Re-emit through the vector pipeline so the copy honours the requested size and colour
                // instead of being a byte-for-byte duplicate of the original.
                val preview = service.resourceArtwork(source)
                    ?: return@background UiIconImportResult(ok = false, message = "Could not read $sourcePath")
                val spec = (preview as? DrawablePreview.Vector)?.spec
                val bytes = if (spec == null) {
                    // Not a vector (a shape, a layer-list, a selector): copy the file as it stands.
                    runCatching { Files.readAllBytes(source) }.getOrNull()
                        ?: return@background UiIconImportResult(ok = false, message = "Could not read $sourcePath")
                } else {
                    service.vectorXml(IconArtwork(spec), request.sizeDp, request.colorArgb).encodeToByteArray()
                }
                finish(service.write(target, resTypeOf(request.resType), request.name, "xml", bytes, request.overwrite))
            } else {
                val bytes = runCatching { Files.readAllBytes(source) }.getOrNull()
                    ?: return@background UiIconImportResult(ok = false, message = "Could not read $sourcePath")
                finish(
                    service.write(
                        target, resTypeOf(request.resType), request.name,
                        extension.ifEmpty { "png" }, bytes, request.overwrite,
                    ),
                )
            }
        }
    }

    override suspend fun previewSvg(svgText: String, colorArgb: Long?): UiIconArtwork? {
        val service = icons ?: return null
        return ctx.background("icons.previewSvg") {
            service.convertSvg(svgText, sizeDp = null, colorArgb = colorArgb)?.let {
                UiIconArtwork(DrawableMapping.toUi(DrawablePreview.Vector(it.spec)), it.warnings)
            }
        }
    }

    // --- Compose icons -------------------------------------------------------------------------------

    override suspend fun composeIcons(moduleName: String?): List<UiIconEntry> {
        val service = icons ?: return emptyList()
        return ctx.background("icons.composeIcons") {
            service.composeIcons(moduleName).map { entry ->
                UiIconEntry(
                    repoId = COMPOSE_REPO_ID,
                    name = entry.name,
                    displayName = ComposeIconIndex.displayName(entry.name),
                    styles = entry.styles.toList(),
                    // The library's own naming has "filled" as a style rather than a fill flag, so the fill
                    // toggle would be a second way to say the same thing.
                    supportsFill = false,
                )
            }
        }
    }

    override suspend fun composeIconArtwork(name: String, variant: UiIconVariant): UiIconArtwork? {
        val service = icons ?: return null
        return ctx.background("icons.composeArtwork") {
            service.composeIconArtwork(name, variant.toEngine())?.let {
                UiIconArtwork(DrawableMapping.toUi(DrawablePreview.Vector(it.spec)), it.warnings)
            }
        }
    }

    override suspend fun iconInsertion(
        path: String,
        text: String,
        caret: Int,
        ref: UiIconRef,
    ): List<UiTextEdit> {
        val service = icons ?: return emptyList()
        return ctx.background("icons.insertion") {
            val target = UiInsertionTarget(
                path = path,
                composeContext = IconSnippets.looksLikeCompose(text),
                insideXmlAttributeValue = IconSnippets.insideXmlAttributeValue(text, caret),
            )
            val snippet = IconSnippets.snippet(ref, target) ?: return@background emptyList()
            val at = caret.coerceIn(0, text.length)
            val edits = ArrayList<UiTextEdit>()
            edits += UiTextEdit(at, at, snippet)
            for (import in IconSnippets.imports(ref, target) + rClassImport(path, text, ref, target)) {
                importEdit(text, import)?.let { edits += it }
            }
            edits
        }
    }

    /**
     * The `R` class import a snippet needs, or nothing. `R` is generated into the module's namespace, so a
     * file already in that package refers to it unqualified and importing it would be redundant (and flagged).
     */
    private fun rClassImport(
        path: String,
        text: String,
        ref: UiIconRef,
        target: UiInsertionTarget,
    ): List<String> {
        if (!IconSnippets.needsRClass(ref, target)) return emptyList()
        val namespace = icons?.namespaceForFile(Paths.get(path))?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val filePackage = PACKAGE_LINE.find(text)?.groupValues?.get(1)?.trim()
        if (filePackage == namespace) return emptyList()
        return listOf("$namespace.R")
    }

    /**
     * An edit that adds `import [fqn]` to [text], or null when it is already there. Inserted after the last
     * existing import (or after the package line), which is where the import optimiser would keep it.
     */
    private fun importEdit(text: String, fqn: String): UiTextEdit? {
        val alreadyThere = Regex("""^\s*import\s+${Regex.escape(fqn)}\s*;?\s*$""", RegexOption.MULTILINE)
        if (alreadyThere.containsMatchIn(text)) return null
        val lastImport = IMPORT_LINE.findAll(text).lastOrNull()
        val anchor = lastImport ?: PACKAGE_LINE.find(text)
        val at = anchor?.range?.last?.plus(1) ?: 0
        // Java terminates an import with a semicolon and Kotlin does not; follow whatever the file already does.
        val terminator = if (anchor?.value?.trimEnd()?.endsWith(";") == true) ";" else ""
        val prefix = if (anchor == null) "" else "\n"
        val suffix = if (anchor == null) "\n" else ""
        return UiTextEdit(at, at, prefix + "import " + fqn + terminator + suffix)
    }

    // --- the app icon --------------------------------------------------------------------------------

    override suspend fun launcherIcon(moduleName: String?): UiAppIconState? {
        val service = icons ?: return null
        return ctx.background("icons.launcherIcon") {
            service.launcherIcon(moduleName)?.let { state ->
                UiAppIconState(
                    moduleName = state.module.name,
                    iconRef = state.iconRef,
                    roundIconRef = state.roundIconRef,
                    current = state.drawable?.let(DrawableMapping::toUi),
                    currentBytes = state.rasterPath?.let { path -> runCatching { Files.readAllBytes(path) }.getOrNull() },
                )
            }
        }
    }

    override suspend fun planAppIcon(spec: UiAppIconSpec): UiAppIconPlan? {
        val service = icons ?: return null
        return ctx.background("icons.planAppIcon") {
            val target = service.targetFor(spec.moduleName) ?: return@background null
            val warnings = ArrayList<String>()
            val plan = service.planAppIcon(spec.toEngine(service, warnings), target)
            UiAppIconPlan(
                resDirPath = target.resDir.toString(),
                files = plan.files.map { it.relativePath },
                rasters = plan.files.filterIsInstance<AppIconFile.Raster>().map {
                    UiAppIconRaster(it.relativePath, it.pixels, it.round, it.opaque)
                },
                replacing = plan.replacing,
                manifestChange = plan.manifest?.let { edit ->
                    listOfNotNull("android:icon=${edit.iconRef}", edit.roundIconRef?.let { "android:roundIcon=$it" })
                        .joinToString(", ")
                },
                warnings = warnings + plan.warnings,
            )
        }
    }

    override suspend fun previewAppIcon(spec: UiAppIconSpec): UiAppIconPreview? {
        val service = icons ?: return null
        return ctx.background("icons.previewAppIcon") {
            val warnings = ArrayList<String>()
            val background = service.resolveLayer(spec.background.toSource(), warnings)
            val foreground = service.resolveLayer(spec.foreground.toSource(), warnings)
            val monochrome = service.resolveLayer(spec.monochrome.toSource(), warnings)
            val images = HashMap<String, ByteArray>()
            (background as? AppIconLayer.Raster)?.let { images["background"] = it.bytes }
            (foreground as? AppIconLayer.Raster)?.let { images["foreground"] = it.bytes }
            UiAppIconPreview(
                // A flat colour has no geometry, so it arrives as a solid-colour drawable the canvas fills with.
                background = (background as? AppIconLayer.Color)?.let { UiDrawable.SolidColor(it.argb) }
                    ?: service.composedLayer(background)?.let { DrawableMapping.toUi(DrawablePreview.Vector(it)) },
                foreground = service.composedLayer(foreground)?.let { DrawableMapping.toUi(DrawablePreview.Vector(it)) },
                monochrome = service.composedLayer(monochrome)?.let { DrawableMapping.toUi(DrawablePreview.Vector(it)) },
                images = images,
                warnings = warnings,
            )
        }
    }

    override suspend fun applyAppIcon(spec: UiAppIconSpec, rasters: List<UiRasterFile>): UiIconImportResult {
        val service = icons ?: return noProject()
        return ctx.background("icons.applyAppIcon") {
            val target = service.targetFor(spec.moduleName) ?: return@background noTarget()
            val warnings = ArrayList<String>()
            val plan = service.planAppIcon(spec.toEngine(service, warnings), target)
            val bytes = rasters.associate { it.relativePath to it.bytes }
            when (val outcome = service.applyAppIcon(plan, target, bytes)) {
                is IconManagerService.ApplyOutcome.Failed ->
                    UiIconImportResult(ok = false, message = outcome.reason)

                is IconManagerService.ApplyOutcome.Written -> {
                    ctx.bumpFileSystemEpoch()
                    val notes = warnings + plan.warnings +
                        outcome.skippedRasters.map { "Skipped $it: no rendered image was supplied" } +
                        if (plan.manifest != null && !outcome.manifestUpdated) {
                            listOf("The manifest already pointed at this icon, so it was left unchanged")
                        } else {
                            emptyList()
                        }
                    UiIconImportResult(
                        ok = true,
                        path = outcome.files.firstOrNull()?.toString(),
                        warnings = notes,
                    )
                }
            }
        }
    }

    /** A UI spec, with each layer resolved against the project. */
    private fun UiAppIconSpec.toEngine(service: IconManagerService, warnings: MutableList<String>) = AppIconSpec(
        name = name,
        background = service.resolveLayer(background.toSource(), warnings),
        foreground = service.resolveLayer(foreground.toSource(), warnings),
        monochrome = service.resolveLayer(monochrome.toSource(), warnings),
        generateRasters = generateRasters,
        generateRoundIcon = generateRoundIcon,
        generatePlayStoreIcon = generatePlayStoreIcon,
    )

    private fun UiIconLayer.toSource(): IconManagerService.LayerSource = when (this) {
        UiIconLayer.None -> IconManagerService.LayerSource.None
        is UiIconLayer.Color -> IconManagerService.LayerSource.Flat(argb)
        is UiIconLayer.RepoIcon -> IconManagerService.LayerSource.Repo(
            repoId = repoId, name = name, variant = variant.toEngine(),
            scale = scale, offsetX = offsetX, offsetY = offsetY, tintArgb = tintArgb,
        )

        is UiIconLayer.Resource -> IconManagerService.LayerSource.Resource(
            path = path, scale = scale, offsetX = offsetX, offsetY = offsetY, tintArgb = tintArgb,
        )

        is UiIconLayer.ImageFile -> IconManagerService.LayerSource.ImageFile(path)
    }

    // --- helpers -------------------------------------------------------------------------------------

    private fun writeText(
        service: IconManagerService,
        request: UiIconImport,
        content: String,
        warnings: List<String>,
    ): UiIconImportResult {
        val target = target(service, request) ?: return noTarget()
        val outcome = service.write(
            target = target,
            resType = resTypeOf(request.resType),
            name = request.name,
            extension = "xml",
            bytes = content.encodeToByteArray(),
            overwrite = request.overwrite,
        )
        return finish(outcome, warnings)
    }

    private fun target(service: IconManagerService, request: UiIconImport): IconManagerService.Target? =
        service.targets().firstOrNull { it.matches(request.target) }

    /** Map a write outcome onto the UI result, bumping the file-system epoch when something was written. */
    private fun finish(
        outcome: IconManagerService.WriteOutcome,
        warnings: List<String> = emptyList(),
    ): UiIconImportResult = when (outcome) {
        is IconManagerService.WriteOutcome.Written -> {
            ctx.bumpFileSystemEpoch()
            UiIconImportResult(ok = true, path = outcome.path.toString(), warnings = warnings)
        }

        is IconManagerService.WriteOutcome.Conflict -> UiIconImportResult(
            ok = false,
            conflictPath = outcome.existing.toString(),
            message = "A resource with that name already exists",
        )

        is IconManagerService.WriteOutcome.Invalid -> UiIconImportResult(ok = false, message = outcome.reason)
        is IconManagerService.WriteOutcome.Failed -> UiIconImportResult(ok = false, message = outcome.reason)
    }

    private fun toUiEntry(entry: IconEntry) = UiIconEntry(
        repoId = entry.repositoryId,
        name = entry.name,
        displayName = entry.displayName,
        category = entry.category,
        styles = entry.styles.map { it.name.lowercase() }.sorted(),
        supportsFill = entry.supportsFill,
    )

    private fun IconManagerService.Target.toUi() = UiIconTarget(
        moduleName = module.name,
        sourceSetName = sourceSetName,
        resDirPath = resDir.toString(),
        isDefault = isDefault,
    )

    /** A UI target refers to an engine target by its res directory, which is unique per (module, source set). */
    private fun IconManagerService.Target.matches(ui: UiIconTarget): Boolean =
        resDir == pathOf(ui.resDirPath)

    private fun pathOf(raw: String): Path = Paths.get(raw)

    private fun UiIconVariant.toEngine() = IconVariant(IconManagerService.styleOf(style), filled)

    private fun resTypeOf(raw: String): ResourceType =
        if (raw.equals("mipmap", ignoreCase = true)) ResourceType.MIPMAP else ResourceType.DRAWABLE

    private fun noProject() = UiIconImportResult(ok = false, message = "No project is open")

    private fun noTarget() =
        UiIconImportResult(ok = false, message = "That module has no res/ directory to write to")

    private companion object {
        val RASTER_EXTENSIONS = setOf("png", "webp", "jpg", "jpeg", "gif")

        /** The pseudo-repository id Compose icons carry, so the UI can tell them apart from a real one. */
        const val COMPOSE_REPO_ID = "compose-icons"

        val PACKAGE_LINE = Regex("""^[ \t]*package[ \t]+([\w.]+)[ \t]*;?[ \t]*$""", RegexOption.MULTILINE)
        val IMPORT_LINE = Regex("""^[ \t]*import[ \t]+\S+.*$""", RegexOption.MULTILINE)
    }
}
