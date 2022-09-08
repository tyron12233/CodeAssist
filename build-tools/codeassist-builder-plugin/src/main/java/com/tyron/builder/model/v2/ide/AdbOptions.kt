package com.tyron.builder.model.v2.ide

import com.tyron.builder.model.v2.AndroidModel

/**
 * Options for adb.
 *
 * @since 4.2
 */
interface AdbOptions: AndroidModel {

    /** The time out used for all adb operations. */
    val timeOutInMs: Int

    /** The list of APK installation options. */
    val installOptions: Collection<String>?
}
