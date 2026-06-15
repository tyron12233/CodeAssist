package dev.ide.android.support.templates

/**
 * Shared starter assets for a generated Android app — the launcher icon set and the app theme — used by
 * both [AndroidAppTemplate] and the bundled `SampleAndroidProject` so they stay identical. Everything is
 * **text** (no binary assets) and **dependency-free** (framework `android:Theme.Material*`, vector
 * drawables), which keeps the scaffold's `writeText` seam and on-device generation working unchanged.
 *
 * The launcher icon is a vector **adaptive icon** (the Android robot): a foreground + background vector in
 * `res/drawable`, an `<adaptive-icon>` in `mipmap-anydpi-v26` for API 26+, and a `<layer-list>` of the same
 * two vectors in `mipmap/` as the API < 26 fallback (default minSdk is 24, below adaptive-icon support).
 */
object AndroidAppAssets {

    /** The launcher-icon background colour (also the robot's "eye" cut-outs), as an editable `@color`. */
    const val ICON_BACKGROUND_COLOR = "#3DDC84"

    /** The `<color>` line callers must add to `res/values/colors.xml` for the icon to resolve. */
    const val ICON_BACKGROUND_COLOR_XML = """<color name="ic_launcher_background">$ICON_BACKGROUND_COLOR</color>"""

    /** Launcher-icon resource files: (path relative to the module's `res/` dir, file content). */
    val launcherIconResFiles: List<Pair<String, String>> = listOf(
        "drawable/ic_launcher_background.xml" to ICON_BACKGROUND.trimIndent(),
        "drawable/ic_launcher_foreground.xml" to ICON_FOREGROUND.trimIndent(),
        "mipmap-anydpi-v26/ic_launcher.xml" to ADAPTIVE_ICON.trimIndent(),
        "mipmap-anydpi-v26/ic_launcher_round.xml" to ADAPTIVE_ICON.trimIndent(),
        "mipmap/ic_launcher.xml" to LEGACY_ICON.trimIndent(),
        "mipmap/ic_launcher_round.xml" to LEGACY_ICON.trimIndent(),
    )

    /** The app theme (`res/values/themes.xml`) — light Material, framework-only. */
    val themesXml: String = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <style name="Theme.App" parent="android:Theme.Material.Light">
                <item name="android:colorPrimary">@color/primary</item>
            </style>
        </resources>
    """.trimIndent()

    /** The dark variant (`res/values-night/themes.xml`) — used automatically in system dark mode. */
    val themesNightXml: String = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <style name="Theme.App" parent="android:Theme.Material">
                <item name="android:colorPrimary">@color/primary</item>
            </style>
        </resources>
    """.trimIndent()
}

private val ICON_BACKGROUND = """
    <?xml version="1.0" encoding="utf-8"?>
    <vector xmlns:android="http://schemas.android.com/apk/res/android"
        android:width="108dp" android:height="108dp"
        android:viewportWidth="108" android:viewportHeight="108">
        <path android:fillColor="@color/ic_launcher_background" android:pathData="M0,0h108v108h-108z"/>
    </vector>
"""

private val ICON_FOREGROUND = """
    <?xml version="1.0" encoding="utf-8"?>
    <vector xmlns:android="http://schemas.android.com/apk/res/android"
        android:width="108dp" android:height="108dp"
        android:viewportWidth="108" android:viewportHeight="108">
        <path android:strokeColor="#FFFFFF" android:strokeWidth="2" android:strokeLineCap="round" android:pathData="M45,45L40,37"/>
        <path android:strokeColor="#FFFFFF" android:strokeWidth="2" android:strokeLineCap="round" android:pathData="M63,45L68,37"/>
        <path android:fillColor="#FFFFFF" android:pathData="M38,72L38,58A16,16 0 0 1 70,58L70,72Z"/>
        <path android:fillColor="@color/ic_launcher_background" android:pathData="M45,54a2,2 0 1 0 4,0a2,2 0 1 0 -4,0"/>
        <path android:fillColor="@color/ic_launcher_background" android:pathData="M59,54a2,2 0 1 0 4,0a2,2 0 1 0 -4,0"/>
    </vector>
"""

private val ADAPTIVE_ICON = """
    <?xml version="1.0" encoding="utf-8"?>
    <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
        <background android:drawable="@drawable/ic_launcher_background"/>
        <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    </adaptive-icon>
"""

// API < 26 has no <adaptive-icon>; compose the same two vectors with a layer-list.
private val LEGACY_ICON = """
    <?xml version="1.0" encoding="utf-8"?>
    <layer-list xmlns:android="http://schemas.android.com/apk/res/android">
        <item android:drawable="@drawable/ic_launcher_background"/>
        <item android:drawable="@drawable/ic_launcher_foreground"/>
    </layer-list>
"""
