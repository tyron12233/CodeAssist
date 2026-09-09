package dev.ide.android.support.tools

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Reads and validates an AAR's `aar-metadata.properties` — the file AGP (4.1+) writes into every AAR at
 * `META-INF/com/android/build/gradle/aar-metadata.properties`, carrying the constraints a *consumer* must
 * satisfy. This is the on-device analogue of AGP's `CheckAarMetadataTask`.
 *
 * We enforce the one constraint users actually hit: **`minCompileSdk`** — the minimum API level a project
 * must compile against to use the library. A dependency needing a higher `minCompileSdk` than the app's
 * [AndroidFacet.compileSdk][dev.ide.android.support.AndroidFacet.compileSdk] is a build error (a class
 * compiled against, say, API 34 references symbols absent from an API 33 `android.jar`).
 *
 * Faithful to AGP's reader: every field is optional; a missing key means "no constraint" (so an older or
 * non-AGP AAR with no metadata file imposes nothing — [read] returns an empty [Info]). Other keys AGP checks
 * (`minCompileSdkExtension`, `minAndroidGradlePluginVersion`, `forceCompileSdkPreview`, core-library
 * desugaring) are parsed for completeness but not enforced here — SDK extension levels and an AGP version have
 * no meaning in this build, and enforcing them would only produce false failures with no escape hatch.
 */
object AarMetadata {

    /** The metadata file's path inside an AAR (AGP's `AAR_METADATA_ENTRY_PATH`). */
    const val ENTRY_PATH = "META-INF/com/android/build/gradle/aar-metadata.properties"

    private const val MIN_COMPILE_SDK = "minCompileSdk"
    private const val MIN_COMPILE_SDK_EXTENSION = "minCompileSdkExtension"
    private const val MIN_AGP_VERSION = "minAndroidGradlePluginVersion"
    private const val FORCE_COMPILE_SDK_PREVIEW = "forceCompileSdkPreview"
    private const val CORE_LIBRARY_DESUGARING_ENABLED = "coreLibraryDesugaringEnabled"
    private const val AAR_FORMAT_VERSION = "aarFormatVersion"
    private const val AAR_METADATA_VERSION = "aarMetadataVersion"

    /** Raw metadata values (all optional; null = key absent). Parsed lazily by the checks so an invalid value
     *  surfaces as a diagnostic rather than a parse crash — mirroring AGP's `AarMetadataReader`. */
    data class Info(
        val minCompileSdk: String? = null,
        val minCompileSdkExtension: String? = null,
        val minAgpVersion: String? = null,
        val forceCompileSdkPreview: String? = null,
        val coreLibraryDesugaringEnabled: String? = null,
        val aarFormatVersion: String? = null,
        val aarMetadataVersion: String? = null,
    ) {
        val isEmpty: Boolean get() =
            minCompileSdk == null && minCompileSdkExtension == null && minAgpVersion == null &&
                forceCompileSdkPreview == null && coreLibraryDesugaringEnabled == null &&
                aarFormatVersion == null && aarMetadataVersion == null
    }

    /** Parse a metadata `.properties` file (standard `java.util.Properties`); a missing/unreadable file → empty. */
    fun read(file: Path): Info {
        if (!Files.isRegularFile(file)) return Info()
        val props = Properties()
        runCatching { Files.newInputStream(file).use { props.load(it) } }.getOrElse { return Info() }
        fun v(key: String) = props.getProperty(key)?.trim()?.ifEmpty { null }
        return Info(
            minCompileSdk = v(MIN_COMPILE_SDK),
            minCompileSdkExtension = v(MIN_COMPILE_SDK_EXTENSION),
            minAgpVersion = v(MIN_AGP_VERSION),
            forceCompileSdkPreview = v(FORCE_COMPILE_SDK_PREVIEW),
            coreLibraryDesugaringEnabled = v(CORE_LIBRARY_DESUGARING_ENABLED),
            aarFormatVersion = v(AAR_FORMAT_VERSION),
            aarMetadataVersion = v(AAR_METADATA_VERSION),
        )
    }

    /**
     * Check one library's [info] against the consuming app's [compileSdk]. Returns the error messages (empty =
     * compatible). [name] identifies the dependency in the message. Pure — no IO — so it is unit-testable.
     */
    fun check(compileSdk: Int, name: String, info: Info): List<String> {
        val raw = info.minCompileSdk ?: return emptyList()
        val min = raw.toIntOrNull()
            ?: return listOf("The AAR metadata for dependency '$name' has an invalid minCompileSdk value ($raw); it must be an integer.")
        if (min <= compileSdk) return emptyList()
        return listOf(
            "Dependency '$name' requires libraries and applications that depend on it to compile against " +
                "version $min or later of the Android APIs.\n" +
                "This module is currently compiled against android-$compileSdk.\n" +
                "Recommended action: raise this module's compileSdk to at least $min.\n" +
                "(compileSdk, which lets newer APIs be used, is separate from targetSdk (runtime behavior) and " +
                "minSdk (device support).)"
        )
    }
}
