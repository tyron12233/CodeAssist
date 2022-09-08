package com.tyron.builder.model.v2.ide

import com.tyron.builder.model.v2.AndroidModel
import java.io.File

/**
 * Information for privacy sandbox SDK APKs.
 *
 * See https://developer.android.com/design-for-safety/privacy-sandbox for more info.
 * @since 7.5
 */
interface PrivacySandboxSdkInfo: AndroidModel {
    /** The task to invoke to build the privacy sandbox SDK */
    val task: String

    /** The location that the privacy sandbox SDKs will be extracted */
    val outputListingFile: File
}
