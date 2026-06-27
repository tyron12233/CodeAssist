package dev.ide.lang.kotlin.interp

/**
 * The androidx `@Preview` constant tables the annotation parser and editor completion both read: `Configuration`
 * UI-mode bits, the `Devices.*` id strings, the canonical argument order, and the built-in MultiPreview
 * expansions. Kept here (not resolved from the classpath) because these are stable platform/library constants
 * and a parse-time evaluator needs them without a full binding resolve.
 */
object PreviewConstants {

    // android.content.res.Configuration UI-mode bits (only the subset a preview meaningfully uses).
    const val UI_MODE_NIGHT_MASK = 0x30
    const val UI_MODE_NIGHT_NO = 0x10
    const val UI_MODE_NIGHT_YES = 0x20

    /** `Configuration.UI_MODE_*` constants by simple name (the parser keys on the last name segment). */
    val uiModeConstants: Map<String, Int> = mapOf(
        "UI_MODE_NIGHT_UNDEFINED" to 0x00,
        "UI_MODE_NIGHT_NO" to UI_MODE_NIGHT_NO,
        "UI_MODE_NIGHT_YES" to UI_MODE_NIGHT_YES,
        "UI_MODE_NIGHT_MASK" to UI_MODE_NIGHT_MASK,
        "UI_MODE_TYPE_UNDEFINED" to 0x00,
        "UI_MODE_TYPE_NORMAL" to 0x01,
        "UI_MODE_TYPE_DESK" to 0x02,
        "UI_MODE_TYPE_CAR" to 0x03,
        "UI_MODE_TYPE_TELEVISION" to 0x04,
        "UI_MODE_TYPE_APPLIANCE" to 0x05,
        "UI_MODE_TYPE_WATCH" to 0x06,
        "UI_MODE_TYPE_VR_HEADSET" to 0x07,
        "UI_MODE_TYPE_MASK" to 0x0f,
    )

    /** `androidx.compose.ui.tooling.preview.Devices.*` id strings by simple name. */
    val deviceConstants: Map<String, String> = mapOf(
        "DEFAULT" to "",
        "NEXUS_7" to "id:Nexus 7",
        "NEXUS_7_2013" to "id:Nexus 7 2013",
        "NEXUS_5" to "id:Nexus 5",
        "NEXUS_6" to "id:Nexus 6",
        "NEXUS_9" to "id:Nexus 9",
        "NEXUS_10" to "name:Nexus 10",
        "NEXUS_5X" to "id:Nexus 5X",
        "NEXUS_6P" to "id:Nexus 6P",
        "PIXEL_C" to "id:pixel_c",
        "PIXEL" to "id:pixel",
        "PIXEL_XL" to "id:pixel_xl",
        "PIXEL_2" to "id:pixel_2",
        "PIXEL_2_XL" to "id:pixel_2_xl",
        "PIXEL_3" to "id:pixel_3",
        "PIXEL_3_XL" to "id:pixel_3_xl",
        "PIXEL_3A" to "id:pixel_3a",
        "PIXEL_3A_XL" to "id:pixel_3a_xl",
        "PIXEL_4" to "id:pixel_4",
        "PIXEL_4_XL" to "id:pixel_4_xl",
        "PIXEL_4A" to "id:pixel_4a",
        "PIXEL_5" to "id:pixel_5",
        "PIXEL_6" to "id:pixel_6",
        "PIXEL_6_PRO" to "id:pixel_6_pro",
        "PIXEL_7" to "id:pixel_7",
        "PIXEL_7_PRO" to "id:pixel_7_pro",
        "PIXEL_FOLD" to "id:pixel_fold",
        "PIXEL_TABLET" to "id:pixel_tablet",
        "AUTOMOTIVE_1024p" to "id:automotive_1024p_landscape",
        "WEAR_OS_LARGE_ROUND" to "id:wearos_large_round",
        "WEAR_OS_SMALL_ROUND" to "id:wearos_small_round",
        "WEAR_OS_SQUARE" to "id:wearos_square",
        "WEAR_OS_RECT" to "id:wearos_rect",
        "TV_720p" to "spec:shape=Normal,width=1280,height=720,unit=dp,dpi=420",
        "TV_1080p" to "spec:shape=Normal,width=1920,height=1080,unit=dp,dpi=420",
        // Reference devices (Compose tooling shorthands).
        "PHONE" to "spec:width=411dp,height=891dp",
        "FOLDABLE" to "spec:width=673dp,height=841dp",
        "TABLET" to "spec:width=1280dp,height=800dp,dpi=240",
        "DESKTOP" to "spec:width=1920dp,height=1080dp,dpi=160",
    )

    /** The positional order of `@Preview`'s parameters (so positional args bind to the right field). */
    val previewArgOrder: List<String> = listOf(
        "name", "group", "apiLevel", "widthDp", "heightDp", "locale", "fontScale",
        "showSystemUi", "showBackground", "backgroundColor", "uiMode", "device", "wallpaper",
    )

    /** Every `@Preview` argument name (drives editor completion of argument names). */
    val previewArgNames: List<String> = previewArgOrder.filter { it != "wallpaper" }

    /** The official `@PreviewFontScale` set (matches Compose tooling: 85% .. 200%). */
    val fontScaleSet: List<Pair<String, Float>> = listOf(
        "85%" to 0.85f, "100%" to 1f, "115%" to 1.15f, "130%" to 1.3f,
        "150%" to 1.5f, "180%" to 1.8f, "200%" to 2f,
    )

    /**
     * The built-in MultiPreview annotations Compose tooling ships (`androidx.compose.ui.tooling.preview`),
     * each expanded to the set of [PreviewConfig]s it stands for. Keyed by annotation simple name so a
     * `@PreviewLightDark` on a composable expands without resolving the library type.
     */
    val builtinMultiPreviews: Map<String, List<PreviewConfig>> = buildMap {
        put(
            "PreviewLightDark", listOf(
                PreviewConfig(name = "Light", uiMode = UI_MODE_NIGHT_NO),
                PreviewConfig(name = "Dark", uiMode = UI_MODE_NIGHT_YES),
            ),
        )
        put("PreviewFontScale", fontScaleSet.map { (label, scale) -> PreviewConfig(name = label, fontScale = scale) })
        put(
            "PreviewScreenSizes", listOf(
                PreviewConfig(name = "Phone", device = deviceConstants.getValue("PHONE")),
                PreviewConfig(name = "Foldable", device = deviceConstants.getValue("FOLDABLE")),
                PreviewConfig(name = "Tablet", device = deviceConstants.getValue("TABLET")),
                PreviewConfig(name = "Desktop", device = deviceConstants.getValue("DESKTOP")),
            ),
        )
        // Dynamic colors are wallpaper-derived (real API 31 theming the interpreter can't reproduce); render
        // the light/dark pair so the entry at least appears, rather than dropping it.
        put(
            "PreviewDynamicColors", listOf(
                PreviewConfig(name = "Dynamic Light", uiMode = UI_MODE_NIGHT_NO),
                PreviewConfig(name = "Dynamic Dark", uiMode = UI_MODE_NIGHT_YES),
            ),
        )
    }
}
