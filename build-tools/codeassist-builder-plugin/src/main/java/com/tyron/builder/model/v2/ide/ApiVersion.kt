package com.tyron.builder.model.v2.ide

import com.tyron.builder.model.v2.AndroidModel

/**
 * Represents the version of an Android Platform.
 *
 * A version is defined by an API level and an optional code name.
 *
 * Release versions of the Android platform are identified by their API level (integer),
 * (technically the code name for release version is "REL" but this class will return
 * `null` instead.)
 *
 * Preview versions of the platform are identified by a code name. Their API level
 * is usually set to the value of the previous platform.
 *
 * @since 4.2
 */
interface ApiVersion: AndroidModel {
    /**
     * The api level as an integer.
     *
     * For target that are in preview mode, this can be superseded by [codename].
     *
     * @see codename
     */
    val apiLevel: Int

    /**
     * The version code name if applicable, null otherwise.
     *
     * If the codename is non null, then the API level should be ignored, and this should be
     * used as a unique identifier of the target instead.
     */
    val codename: String?
}
