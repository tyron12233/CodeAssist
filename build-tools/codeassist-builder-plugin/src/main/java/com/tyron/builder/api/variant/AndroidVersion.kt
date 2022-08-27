package com.tyron.builder.api.variant

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional

interface AndroidVersion {

    /**
     * SDK version codes mirroring ones found in Build#VERSION_CODES on Android.
     */
    @get:Input
    val apiLevel: Int

    /**
     * Preview versions of the platform are identified by a code name. Their API level
     * is usually set to the value of the previous platform.
     */
    @get:Input
    @get:Optional
    val codename: String?
}