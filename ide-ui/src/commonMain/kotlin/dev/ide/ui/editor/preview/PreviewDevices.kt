package dev.ide.ui.editor.preview

/**
 * Resolves a `@Preview(device = ...)` spec (or explicit `widthDp`/`heightDp`) to a [DeviceProfile] the preview
 * surface sizes its card from. Handles the named `Devices.*` ids (`"id:pixel_4"`) and the `spec:` form
 * (`"spec:width=411dp,height=891dp,dpi=420"`); an unrecognized id with no explicit size returns null so the
 * surface keeps the user's selected device.
 */
object PreviewDevices {

    /** Android Studio's default device for a sizeless `@Preview(showSystemUi = true)`: a generic phone. Its
     *  dp match the off-screen render's fallback viewport ([DEFAULT_VIEWPORT_WIDTH_DP]/[DEFAULT_VIEWPORT_HEIGHT_DP])
     *  so the card and the streamed frame share one aspect ratio. */
    val DEFAULT_PHONE = DeviceProfile("Phone", 411, 731, 2.625f)

    /** Heights (dp) of the mock status + navigation bars [SystemUiChrome] draws around a
     *  `@Preview(showSystemUi = true)`. Shared so the off-screen render can target the BODY area between them
     *  (device height minus these), letting the streamed frame fill the chrome's content slot with no letterbox. */
    const val STATUS_BAR_DP = 24
    const val NAV_BAR_DP = 38

    /** The off-screen render's fallback viewport (dp) when a preview declares no device/size and doesn't wrap. */
    const val DEFAULT_VIEWPORT_WIDTH_DP = 411
    const val DEFAULT_VIEWPORT_HEIGHT_DP = 731

    /**
     * The logical size (dp) the off-screen preview surface should render at for a given `@Preview`, so the
     * streamed frame matches the card's aspect (device size) instead of a fixed default — the fix for the
     * "letterboxed / doesn't fill the phone" render. Returns null for a **wrap-to-content** preview (no
     * device/size and no system UI), which the caller renders into the default viewport as a max bound and
     * crops to the measured content.
     *
     * For `showSystemUi` the height is the device BODY between the mock status + nav bars, so the frame fills
     * [SystemUiChrome]'s content slot exactly; a sizeless `showSystemUi` preview frames the [DEFAULT_PHONE].
     */
    fun renderSurfaceDp(device: String?, widthDp: Int?, heightDp: Int?, showSystemUi: Boolean): Pair<Int, Int>? {
        val wrap = device.isNullOrBlank() && widthDp == null && heightDp == null && !showSystemUi
        if (wrap) return null
        val profile = resolve(device, widthDp, heightDp) ?: if (showSystemUi) DEFAULT_PHONE else null
        val wDp = profile?.wdp ?: DEFAULT_VIEWPORT_WIDTH_DP
        val hFull = profile?.hdp ?: DEFAULT_VIEWPORT_HEIGHT_DP
        val hDp = if (showSystemUi) (hFull - STATUS_BAR_DP - NAV_BAR_DP).coerceAtLeast(1) else hFull
        return wDp to hDp
    }

    /** Curated logical sizes (dp) + density for the common `Devices.*` ids. Approximate but representative. */
    private val known: Map<String, DeviceProfile> = mapOf(
        "id:pixel" to DeviceProfile("Pixel", 411, 731, 2.6f),
        "id:pixel_xl" to DeviceProfile("Pixel XL", 411, 731, 3.5f),
        "id:pixel_2" to DeviceProfile("Pixel 2", 411, 731, 2.6f),
        "id:pixel_2_xl" to DeviceProfile("Pixel 2 XL", 411, 823, 3.5f),
        "id:pixel_3" to DeviceProfile("Pixel 3", 393, 786, 2.75f),
        "id:pixel_3_xl" to DeviceProfile("Pixel 3 XL", 393, 786, 3.5f),
        "id:pixel_3a" to DeviceProfile("Pixel 3a", 393, 808, 2.75f),
        "id:pixel_4" to DeviceProfile("Pixel 4", 393, 830, 2.75f),
        "id:pixel_4_xl" to DeviceProfile("Pixel 4 XL", 411, 869, 3.5f),
        "id:pixel_4a" to DeviceProfile("Pixel 4a", 393, 851, 2.75f),
        "id:pixel_5" to DeviceProfile("Pixel 5", 393, 851, 2.75f),
        "id:pixel_6" to DeviceProfile("Pixel 6", 411, 914, 2.625f),
        "id:pixel_6_pro" to DeviceProfile("Pixel 6 Pro", 411, 891, 3.5f),
        "id:pixel_7" to DeviceProfile("Pixel 7", 412, 915, 2.625f),
        "id:pixel_7_pro" to DeviceProfile("Pixel 7 Pro", 412, 892, 3.5f),
        "id:pixel_fold" to DeviceProfile("Pixel Fold", 841, 701, 2.5f),
        "id:pixel_tablet" to DeviceProfile("Pixel Tablet", 1280, 800, 2f),
        "id:Nexus 5" to DeviceProfile("Nexus 5", 360, 640, 3f),
        "id:Nexus 6" to DeviceProfile("Nexus 6", 412, 732, 3.5f),
        "id:Nexus 7" to DeviceProfile("Nexus 7", 600, 960, 2f),
        "id:Nexus 9" to DeviceProfile("Nexus 9", 768, 1024, 2f),
        "id:Nexus 10" to DeviceProfile("Nexus 10", 800, 1280, 2f),
        "id:wearos_small_round" to DeviceProfile("Wear Small", 192, 192, 2f),
        "id:wearos_large_round" to DeviceProfile("Wear Large", 227, 227, 2f),
        "id:wearos_square" to DeviceProfile("Wear Square", 180, 180, 2f),
    )

    fun resolve(device: String?, widthDp: Int?, heightDp: Int?): DeviceProfile? {
        if (!device.isNullOrBlank()) {
            known[device]?.let { return it }
            if (device.startsWith("spec:")) parseSpec(device)?.let { return it }
            // An unknown named device: fall back to an explicit size if any, else let the surface decide.
            return customSize(widthDp, heightDp)
        }
        return customSize(widthDp, heightDp)
    }

    private fun customSize(widthDp: Int?, heightDp: Int?): DeviceProfile? =
        if (widthDp == null && heightDp == null) null
        else DeviceProfile("Custom", widthDp ?: 360, heightDp ?: 800, 2f)

    /** Parse `spec:width=411dp,height=891dp,dpi=420` (or `width=1280,height=720,unit=dp,dpi=420`). Sizes given
     *  in px (no `dp` suffix and `unit != dp`) are converted to dp via `dpi`; density is `dpi/160`. */
    private fun parseSpec(spec: String): DeviceProfile? {
        val kv = spec.removePrefix("spec:").split(",")
            .mapNotNull { it.split("=").takeIf { p -> p.size == 2 }?.let { p -> p[0].trim() to p[1].trim() } }
            .toMap()
        val wRaw = kv["width"] ?: return null
        val hRaw = kv["height"] ?: return null
        val dpi = kv["dpi"]?.toIntOrNull() ?: 420
        val density = (dpi / 160f).coerceIn(0.75f, 4f)
        val unitIsDp = kv["unit"]?.equals("dp", ignoreCase = true) == true
        fun toDp(raw: String): Int? {
            val isDp = raw.endsWith("dp", ignoreCase = true) || unitIsDp
            val n = raw.removeSuffix("dp").removeSuffix("px").trim().toFloatOrNull() ?: return null
            return (if (isDp) n else n / (dpi / 160f)).toInt()
        }
        val w = toDp(wRaw) ?: return null
        val h = toDp(hRaw) ?: return null
        return DeviceProfile("Custom", w, h, density)
    }
}
