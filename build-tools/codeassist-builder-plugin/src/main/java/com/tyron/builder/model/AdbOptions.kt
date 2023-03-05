package com.tyron.builder.model

/**
 * Options for adb.
 */
interface AdbOptions {

    /** The time out used for all adb operations. */
    val timeOutInMs: Int

    /** The list of APK installation options. */
    val installOptions: Collection<String>?
}