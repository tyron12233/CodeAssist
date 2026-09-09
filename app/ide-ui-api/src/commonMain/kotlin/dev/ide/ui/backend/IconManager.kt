package dev.ide.ui.backend

/**
 * The Icon Manager's neutral DTOs: browsing icon repositories, listing what a module's `res/` already holds,
 * importing an icon as a drawable, and editing the app's launcher icon.
 *
 * Like the rest of this module these carry no engine or Compose types. Geometry always arrives as a
 * [UiDrawable], so the icon grid, the resource preview pane and the Compose preview all render icons through
 * one path.
 */

// ---------------------------------------------------------------------------
// Repositories and browsing
// ---------------------------------------------------------------------------

/** A browsable source of icons: the bundled Material subset, a remote set, or a plugin's own library. */
data class UiIconRepo(
    val id: String,
    val displayName: String,
    val license: String,
    val attribution: String?,
    /** True when this repository downloads its catalogue; the picker gates loading behind an explicit tap. */
    val requiresNetwork: Boolean,
    /** True once the catalogue is available; a network repository starts false. */
    val loaded: Boolean,
    val iconCount: Int,
)

/** One icon in a repository listing. Geometry is fetched separately, per icon and per variant. */
data class UiIconEntry(
    val repoId: String,
    val name: String,
    val displayName: String,
    val category: String? = null,
    /** The style families available: "outlined", "rounded", "sharp". */
    val styles: List<String> = listOf("outlined"),
    val supportsFill: Boolean = false,
)

/** Which rendering of an icon to show or import. */
data class UiIconVariant(val style: String = "outlined", val filled: Boolean = false)

/** Resolved icon geometry, plus anything lossy about how it was resolved. */
data class UiIconArtwork(val drawable: UiDrawable, val warnings: List<String> = emptyList())

/** The outcome of loading a repository's catalogue. */
data class UiIconLoadResult(val ok: Boolean, val message: String? = null, val iconCount: Int = 0)

// ---------------------------------------------------------------------------
// The project's own resources
// ---------------------------------------------------------------------------

/**
 * A drawable or mipmap resource already in the project, with every configuration it is declared in (density
 * buckets, `night`, `v26`, ...) so the catalogue can show that an icon has, say, a `night` variant.
 */
data class UiResourceIcon(
    val moduleName: String,
    /** "drawable" or "mipmap". */
    val resType: String,
    val name: String,
    val configurations: List<UiResourceConfig>,
)

/** One declaration of a resource: its config qualifier (empty for the default) and the file backing it. */
data class UiResourceConfig(
    val qualifier: String,
    val path: String,
    /** True for a PNG/WebP/JPEG file, false for XML the drawable parser can render. */
    val isRaster: Boolean,
)

// ---------------------------------------------------------------------------
// Import targets
// ---------------------------------------------------------------------------

/**
 * Where a generated asset is written: a module's source set and the `res/` directory that belongs to it.
 * Per-flavour and per-build-type icons are a real need, so the target is a source set rather than just a
 * module.
 */
data class UiIconTarget(
    val moduleName: String,
    val sourceSetName: String,
    val resDirPath: String,
    /** True for the target the picker should preselect: the application module's `main`. */
    val isDefault: Boolean = false,
)

/** How to write an imported icon. */
data class UiIconImport(
    val target: UiIconTarget,
    /** "drawable" or "mipmap". */
    val resType: String = "drawable",
    /** Resource name with no extension or path, e.g. `ic_shopping_cart`. */
    val name: String,
    val sizeDp: Float = 24f,
    /** Repaint the artwork this `0xAARRGGBB` colour; null keeps its own colours. */
    val colorArgb: Long? = null,
    /** Replace an existing resource of the same name instead of failing on the conflict. */
    val overwrite: Boolean = false,
)

/**
 * The result of an import. [conflictPath] is set (with [ok] false) when a resource of that name already
 * exists and [UiIconImport.overwrite] was not requested, so the UI can show both and offer to replace.
 */
data class UiIconImportResult(
    val ok: Boolean,
    val path: String? = null,
    val message: String? = null,
    val conflictPath: String? = null,
    val warnings: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// The app's launcher icon
// ---------------------------------------------------------------------------

/**
 * One layer of an adaptive icon. A layer names *where its artwork comes from* rather than carrying the
 * artwork itself: the backend already knows how to resolve a repository icon or a project resource, and
 * shipping geometry back across the seam would mean converting a rendered [UiDrawable] into a vector again.
 */
sealed interface UiIconLayer {

    /** No layer (a transparent background, or no monochrome layer at all). */
    data object None : UiIconLayer

    /** A flat `0xAARRGGBB` fill, written as a colour resource. */
    data class Color(val argb: Long) : UiIconLayer

    /** An icon from a registered repository. */
    data class RepoIcon(
        val repoId: String,
        val name: String,
        val variant: UiIconVariant = UiIconVariant(),
        override val scale: Float = 1f,
        override val offsetX: Float = 0f,
        override val offsetY: Float = 0f,
        override val tintArgb: Long? = null,
    ) : UiIconLayer, UiIconLayerPlacement

    /** A drawable the project already declares. */
    data class Resource(
        val path: String,
        override val scale: Float = 1f,
        override val offsetX: Float = 0f,
        override val offsetY: Float = 0f,
        override val tintArgb: Long? = null,
    ) : UiIconLayer, UiIconLayerPlacement

    /** An image file the user picked from storage, used as-is. */
    data class ImageFile(val path: String) : UiIconLayer
}

/**
 * How a vector layer sits in the icon box: [scale] is a multiple of the adaptive icon's safe zone (1.0 fits
 * the artwork exactly inside it) and [offsetX]/[offsetY] nudge it by a fraction of the whole box.
 */
interface UiIconLayerPlacement {
    val scale: Float
    val offsetX: Float
    val offsetY: Float
    val tintArgb: Long?
}

/** What the app-icon studio should produce. */
data class UiAppIconSpec(
    val moduleName: String,
    /** Base resource name; Android's convention is `ic_launcher`. */
    val name: String = "ic_launcher",
    val background: UiIconLayer = UiIconLayer.Color(0xFFFFFFFF),
    val foreground: UiIconLayer = UiIconLayer.None,
    /** The Android 13+ themed-icon layer; [UiIconLayer.None] skips it. */
    val monochrome: UiIconLayer = UiIconLayer.None,
    val generateRasters: Boolean = true,
    val generateRoundIcon: Boolean = true,
    val generatePlayStoreIcon: Boolean = true,
)

/** The launcher icon a module currently declares, for the studio's "before" state. */
data class UiAppIconState(
    val moduleName: String,
    val iconRef: String?,
    val roundIconRef: String?,
    val current: UiDrawable?,
    /** Raster bytes when the resolved icon is an image file rather than XML. */
    val currentBytes: ByteArray? = null,
)

/**
 * The composed layers of an icon spec, each already placed in the 108-unit adaptive-icon box. This is what
 * the studio draws for its live mask previews, and what it rasterises from: rendering the very same models
 * that were used to compute the layer files is what keeps the preview honest.
 */
data class UiAppIconPreview(
    val background: UiDrawable?,
    val foreground: UiDrawable?,
    val monochrome: UiDrawable?,
    /** Raster bytes for an image layer, keyed "background"/"foreground", when one was used. */
    val images: Map<String, ByteArray> = emptyMap(),
    val warnings: List<String> = emptyList(),
)

/** A raster the host has to render, because rasterising a vector needs a canvas the engine has no access to. */
data class UiAppIconRaster(
    val relativePath: String,
    val pixels: Int,
    /** The circular launcher mask; otherwise the legacy rounded-square one. */
    val round: Boolean,
    /** No transparency allowed (the Play Store listing image). */
    val opaque: Boolean,
)

/**
 * Everything an app-icon change will do, computed before anything is written so the studio can show it.
 * [files] is every path that would be written, relative to [resDirPath]; [replacing] is the subset that
 * already exists.
 */
data class UiAppIconPlan(
    val resDirPath: String,
    val files: List<String>,
    val rasters: List<UiAppIconRaster>,
    val replacing: List<String> = emptyList(),
    val manifestChange: String? = null,
    val warnings: List<String> = emptyList(),
)

/** Encoded bytes for one [UiAppIconRaster] the host rendered. */
data class UiRasterFile(val relativePath: String, val bytes: ByteArray)

// ---------------------------------------------------------------------------
// The service
// ---------------------------------------------------------------------------

/**
 * Browsing, previewing, importing and generating icons: the Icon Manager's backend.
 *
 * Every method has a default so a host that doesn't support icons (a stub backend, a preview) inherits an
 * inert implementation and the screen simply shows nothing to do.
 */
interface IconService {

    // --- repositories ---

    /**
     * The registered icon repositories, in registration order (the bundled one first). Named for icons
     * specifically because `repositories()` already means Maven repositories on the dependency service, and a
     * host that aggregates both services onto one object could not implement both.
     */
    fun iconRepositories(): List<UiIconRepo> = emptyList()

    /** Download [repoId]'s catalogue. Only called after the user asks for a network repository. */
    suspend fun loadRepository(repoId: String): UiIconLoadResult =
        UiIconLoadResult(ok = false, message = "Icon repositories are not available")

    /** Icons in [repoId] matching [query] (blank keeps the repository's own order), best match first. */
    suspend fun searchIcons(repoId: String, query: String, limit: Int = 200): List<UiIconEntry> = emptyList()

    /** [name]'s geometry in [repoId], or null when it can't be resolved. */
    suspend fun iconArtwork(repoId: String, name: String, variant: UiIconVariant = UiIconVariant()): UiIconArtwork? = null

    // --- the project's resources ---

    /** Drawable and mipmap resources in [moduleName], or across every Android module when null. */
    suspend fun projectIcons(moduleName: String? = null): List<UiResourceIcon> = emptyList()

    /** The render-ready model of the resource file at [path] (read from disk), or null. */
    suspend fun resourceArtwork(path: String): UiIconArtwork? = null

    /** Raw bytes of a raster resource at [path], for the grid's bitmap tiles. */
    suspend fun resourceBytes(path: String): ByteArray? = null

    // --- importing ---

    /** The `res/` directories an asset can be written to, default first. */
    fun importTargets(): List<UiIconTarget> = emptyList()

    /** The path of an existing `[resType]/[name]` under [target], or null when the name is free. */
    fun existingResource(target: UiIconTarget, resType: String, name: String): String? = null

    /** Import [name] from [repoId] as a vector drawable. */
    suspend fun importIcon(
        repoId: String,
        name: String,
        variant: UiIconVariant,
        request: UiIconImport,
    ): UiIconImportResult = unsupportedImport()

    /** Import [svgText] (a file the user picked) as a vector drawable, converting it on the way in. */
    suspend fun importSvg(svgText: String, request: UiIconImport): UiIconImportResult = unsupportedImport()

    /** Import already-encoded image [bytes] as a raster resource named `[UiIconImport.name].[extension]`. */
    suspend fun importRaster(bytes: ByteArray, extension: String, request: UiIconImport): UiIconImportResult =
        unsupportedImport()

    /**
     * Copy the resource file at [sourcePath] into another target, which is what "reuse this icon in another
     * module" means. XML is recoloured and resized like any other import; a raster is copied byte for byte.
     */
    suspend fun copyResource(sourcePath: String, request: UiIconImport): UiIconImportResult = unsupportedImport()

    /** Preview what [svgText] converts to, without writing anything. */
    suspend fun previewSvg(svgText: String, colorArgb: Long? = null): UiIconArtwork? = null

    // --- the app icon ---

    /** The launcher icon [moduleName] currently declares (the application module when null). */
    suspend fun launcherIcon(moduleName: String? = null): UiAppIconState? = null

    /** Everything [spec] would write, computed without touching the project. */
    suspend fun planAppIcon(spec: UiAppIconSpec): UiAppIconPlan? = null

    /** [spec]'s layers composed into the icon box, for the studio's preview and its rasteriser. */
    suspend fun previewAppIcon(spec: UiAppIconSpec): UiAppIconPreview? = null

    /**
     * Write [spec]'s files and point the manifest at them. [rasters] supplies the encoded bytes for each
     * [UiAppIconPlan.rasters] entry, which the host renders because rasterising a vector needs a canvas the
     * engine does not have.
     */
    suspend fun applyAppIcon(spec: UiAppIconSpec, rasters: List<UiRasterFile>): UiIconImportResult =
        unsupportedImport()

    // --- Compose icons ---

    /** `Icons.*` properties available on [moduleName]'s classpath, or empty when the library isn't there. */
    suspend fun composeIcons(moduleName: String? = null): List<UiIconEntry> = emptyList()

    /** The real `ImageVector` behind a Compose icon, evaluated through the interpreter. */
    suspend fun composeIconArtwork(name: String, variant: UiIconVariant = UiIconVariant()): UiIconArtwork? = null

    /**
     * The edits that insert [ref] into [path]'s buffer at [caret], written the way that file's language wants
     * (see [IconSnippets]) and with any imports it needs. Empty when the reference has no meaning there.
     */
    suspend fun iconInsertion(path: String, text: String, caret: Int, ref: UiIconRef): List<UiTextEdit> =
        emptyList()

    companion object {
        private fun unsupportedImport() =
            UiIconImportResult(ok = false, message = "Importing icons is not available in this project")

        /** A backend with no icon support at all. */
        val Unsupported: IconService = object : IconService {}
    }
}
