package dev.ide.android.support.resources

import dev.ide.android.support.metadata.AttributeSpec

/**
 * A curated schema of the `AndroidManifest.xml` element + attribute set — the manifest's grammar is small,
 * stable across API levels, and not derivable from `attrs.xml` (manifest attributes live in the framework's
 * compiled resources, not the layout styleables), so a hand-built table is the right tool here. Reuses
 * [AttributeSpec] from [AndroidWidgetCatalog] so the completion adapter treats values identically (enums,
 * flags, booleans, `@type/…` references).
 */
object AndroidManifestCatalog {

    data class Element(
        val tag: String,
        val attributes: List<AttributeSpec> = emptyList(),
        val children: List<String> = emptyList(),
    )

    /** The document root element. */
    const val ROOT = "manifest"

    private val LAUNCH_MODE = listOf("standard", "singleTop", "singleTask", "singleInstance", "singleInstancePerTask")
    private val ORIENTATION = listOf(
        "unspecified", "behind", "landscape", "portrait", "reverseLandscape", "reversePortrait",
        "sensorLandscape", "sensorPortrait", "userLandscape", "userPortrait", "sensor", "fullSensor",
        "nosensor", "user", "fullUser", "locked",
    )
    private val CONFIG_CHANGES = listOf(
        "mcc", "mnc", "locale", "touchscreen", "keyboard", "keyboardHidden", "navigation", "orientation",
        "screenLayout", "uiMode", "screenSize", "smallestScreenSize", "density", "layoutDirection", "fontScale",
    )
    private val SOFT_INPUT = listOf(
        "stateUnspecified", "stateUnchanged", "stateHidden", "stateAlwaysHidden", "stateVisible",
        "stateAlwaysVisible", "adjustUnspecified", "adjustResize", "adjustPan", "adjustNothing",
    )
    private val PROTECTION_LEVEL = listOf("normal", "dangerous", "signature", "signatureOrSystem", "privileged")

    private fun name() = AttributeSpec("android:name")
    private fun bool(n: String) = AttributeSpec("android:$n", boolean = true)
    private fun label() = AttributeSpec("android:label", resourceTypes = listOf(ResourceType.STRING))
    private fun icon(n: String = "icon") =
        AttributeSpec("android:$n", resourceTypes = listOf(ResourceType.DRAWABLE, ResourceType.MIPMAP))
    private fun theme() = AttributeSpec("android:theme", resourceTypes = listOf(ResourceType.STYLE))

    private val elements: Map<String, Element> = listOf(
        Element(ROOT,
            attributes = listOf(
                AttributeSpec("xmlns:android"),
                AttributeSpec("package"),
                AttributeSpec("android:versionCode"),
                AttributeSpec("android:versionName", resourceTypes = listOf(ResourceType.STRING)),
                AttributeSpec("android:sharedUserId"),
                AttributeSpec("android:installLocation", enumValues = listOf("auto", "internalOnly", "preferExternal")),
            ),
            children = listOf(
                "application", "uses-permission", "uses-permission-sdk-23", "permission", "uses-sdk",
                "uses-feature", "supports-screens", "instrumentation", "queries", "uses-configuration",
            ),
        ),
        Element("application",
            attributes = listOf(
                name(), label(), icon(), icon("roundIcon"), icon("banner"), theme(),
                bool("allowBackup"), bool("supportsRtl"), bool("debuggable"), bool("hardwareAccelerated"),
                bool("largeHeap"), bool("usesCleartextTraffic"), bool("requestLegacyExternalStorage"),
                AttributeSpec("android:networkSecurityConfig", resourceTypes = listOf(ResourceType.XML)),
                AttributeSpec("android:appComponentFactory"),
            ),
            children = listOf("activity", "activity-alias", "service", "receiver", "provider", "meta-data", "uses-library", "profileable"),
        ),
        Element("activity",
            attributes = listOf(
                name(), label(), icon(), theme(), bool("exported"),
                AttributeSpec("android:launchMode", enumValues = LAUNCH_MODE),
                AttributeSpec("android:screenOrientation", enumValues = ORIENTATION),
                AttributeSpec("android:configChanges", flags = CONFIG_CHANGES),
                AttributeSpec("android:windowSoftInputMode", flags = SOFT_INPUT),
                AttributeSpec("android:parentActivityName"),
                AttributeSpec("android:permission"), AttributeSpec("android:process"),
                AttributeSpec("android:taskAffinity"), bool("noHistory"), bool("hardwareAccelerated"),
            ),
            children = listOf("intent-filter", "meta-data", "layout"),
        ),
        Element("activity-alias",
            attributes = listOf(name(), label(), icon(), bool("exported"), AttributeSpec("android:targetActivity"), AttributeSpec("android:permission")),
            children = listOf("intent-filter", "meta-data"),
        ),
        Element("service",
            attributes = listOf(name(), bool("exported"), bool("enabled"), AttributeSpec("android:permission"),
                AttributeSpec("android:process"), AttributeSpec("android:foregroundServiceType", flags = listOf(
                    "camera", "connectedDevice", "dataSync", "location", "mediaPlayback", "mediaProjection",
                    "microphone", "phoneCall", "remoteMessaging", "shortService", "specialUse", "systemExempted",
                ))),
            children = listOf("intent-filter", "meta-data"),
        ),
        Element("receiver",
            attributes = listOf(name(), bool("exported"), bool("enabled"), AttributeSpec("android:permission"), AttributeSpec("android:process")),
            children = listOf("intent-filter", "meta-data"),
        ),
        Element("provider",
            attributes = listOf(name(), AttributeSpec("android:authorities"), bool("exported"), bool("grantUriPermissions"),
                AttributeSpec("android:permission"), AttributeSpec("android:readPermission"), AttributeSpec("android:writePermission")),
            children = listOf("meta-data", "grant-uri-permission", "path-permission"),
        ),
        Element("intent-filter",
            attributes = listOf(label(), icon(), AttributeSpec("android:priority"), bool("autoVerify")),
            children = listOf("action", "category", "data"),
        ),
        Element("action", attributes = listOf(name())),
        Element("category", attributes = listOf(name())),
        Element("data", attributes = listOf(
            AttributeSpec("android:scheme"), AttributeSpec("android:host"), AttributeSpec("android:port"),
            AttributeSpec("android:path"), AttributeSpec("android:pathPrefix"), AttributeSpec("android:pathPattern"),
            AttributeSpec("android:mimeType"),
        )),
        Element("meta-data", attributes = listOf(name(), AttributeSpec("android:value"), AttributeSpec("android:resource"))),
        Element("uses-permission", attributes = listOf(name(), AttributeSpec("android:maxSdkVersion"))),
        Element("uses-permission-sdk-23", attributes = listOf(name(), AttributeSpec("android:maxSdkVersion"))),
        Element("uses-sdk", attributes = listOf(
            AttributeSpec("android:minSdkVersion"), AttributeSpec("android:targetSdkVersion"), AttributeSpec("android:maxSdkVersion"),
        )),
        Element("uses-feature", attributes = listOf(name(), bool("required"), AttributeSpec("android:glEsVersion"))),
        Element("uses-library", attributes = listOf(name(), bool("required"))),
        Element("permission", attributes = listOf(name(), label(), AttributeSpec("android:permissionGroup"),
            AttributeSpec("android:protectionLevel", flags = PROTECTION_LEVEL))),
        Element("supports-screens", attributes = listOf(bool("smallScreens"), bool("normalScreens"), bool("largeScreens"), bool("xlargeScreens"), bool("anyDensity"))),
    ).associateBy { it.tag }

    /** Child element tags valid inside [parentTag] (the root's children when [parentTag] is null/unknown). */
    fun childrenOf(parentTag: String?): List<String> {
        if (parentTag == null) return listOf(ROOT)
        return elements[parentTag]?.children ?: emptyList()
    }

    fun attributesFor(tag: String?): List<AttributeSpec> = elements[tag]?.attributes ?: emptyList()

    fun attribute(tag: String?, attributeName: String?): AttributeSpec? =
        attributesFor(tag).firstOrNull { it.name == attributeName }
}
