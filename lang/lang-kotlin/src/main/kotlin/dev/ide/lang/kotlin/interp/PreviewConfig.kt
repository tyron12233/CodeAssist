package dev.ide.lang.kotlin.interp

/**
 * The render-affecting arguments parsed from a `@Preview` annotation (or synthesized by a MultiPreview
 * expansion). A null/absent value means "not specified", so the render surface keeps its own default rather
 * than being forced to a sentinel. Mirrors `androidx.compose.ui.tooling.preview.Preview`'s parameters, minus
 * the ones an in-process interpreter can't meaningfully honor (wallpaper).
 */
data class PreviewConfig(
    /** `name` / `group` are display+organisation only (they label the entry in the preview selector). */
    val name: String? = null,
    val group: String? = null,
    /** Best-effort: the IDE can't change `Build.VERSION.SDK_INT` in-process, so this is shown, not enforced. */
    val apiLevel: Int? = null,
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    val locale: String? = null,
    val fontScale: Float? = null,
    val showSystemUi: Boolean = false,
    val showBackground: Boolean = false,
    /** 0xAARRGGBB, the same packed-ARGB long `@Preview(backgroundColor=...)` uses (0 = unset). */
    val backgroundColor: Long? = null,
    /** `Configuration.UI_MODE_*` bits (the night bit is what the render surface acts on). */
    val uiMode: Int? = null,
    /** The raw device spec as written: an `id:`/`name:`/`spec:` string (resolved to a size by the UI). */
    val device: String? = null,
) {
    /** Whether this config forces night mode (the `UI_MODE_NIGHT_YES` bit is set in [uiMode]). */
    val isNight: Boolean
        get() = uiMode?.let { (it and PreviewConstants.UI_MODE_NIGHT_MASK) == PreviewConstants.UI_MODE_NIGHT_YES } == true
}

/**
 * A `@PreviewParameter` provider bound to a previewed composable's value parameter: the
 * `PreviewParameterProvider` supplies the sample value(s) the preview is rendered with.
 */
data class PreviewParamInfo(
    /** The provider type exactly as written at the call site (`SomeProvider` or a qualified name). */
    val providerName: String,
    /** `@PreviewParameter(limit = N)`; `Int.MAX_VALUE` when unset (all values render). */
    val limit: Int = Int.MAX_VALUE,
)
