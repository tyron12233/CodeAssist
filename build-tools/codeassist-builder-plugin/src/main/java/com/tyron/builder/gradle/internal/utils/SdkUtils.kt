package com.tyron.builder.gradle.internal.utils

import com.tyron.builder.common.AndroidVersion
import java.util.regex.Pattern

data class CompileData(
    val apiLevel: Int? = null,
    val codeName: String? = null,
    val sdkExtension: Int? = null,
    val vendorName: String? = null,
    val addonName: String? = null
) {
    fun isAddon() = vendorName != null && addonName != null
}

fun parseTargetHash(targetHash : String): CompileData  {
    val apiMatcher = API_PATTERN.matcher(targetHash)
    if (apiMatcher.matches()) {
        return CompileData(
            apiLevel = apiMatcher.group(1).toInt(),
            sdkExtension = apiMatcher.group(3)?.toIntOrNull()
        )
    }

    val previewMatcher = FULL_PREVIEW_PATTERN.matcher(targetHash)
    if (previewMatcher.matches()) {
        return CompileData(codeName = previewMatcher.group(1))
    }

    val addonMatcher = ADDON_PATTERN.matcher(targetHash)
    if (addonMatcher.matches()) {
        return CompileData(
            vendorName = addonMatcher.group(1),
            addonName = addonMatcher.group(2),
            apiLevel = addonMatcher.group(3).toInt()
        )
    }

    throw RuntimeException(
        """
                    Unsupported value: $targetHash. Format must be one of:
                    - android-31
                    - android-31-ext2
                    - android-T
                    - vendorName:addonName:31
                    """.trimIndent()
    )
}

fun validatePreviewTargetValue(value: String): String? =
    if (AndroidVersion.PREVIEW_PATTERN.matcher(value).matches()) {
        value
    } else null

private val API_PATTERN: Pattern = Pattern.compile("^android-([0-9]+)(-ext(\\d+))?$")
private val FULL_PREVIEW_PATTERN: Pattern = Pattern.compile("^android-([A-Z][0-9A-Za-z_]*)$")
private val ADDON_PATTERN: Pattern = Pattern.compile("^(.+):(.+):(\\d+)$")
