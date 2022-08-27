package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/**
 * Options for the adb tool.
 */
interface Installation {
    /** The time out used for all adb operations. */
    @get:Incubating
    @set:Incubating
    var timeOutInMs: Int

    /** The list of FULL_APK installation options. */
    @get:Incubating
    @set:Incubating
    var installOptions: Collection<String>?

    /** Sets the list of FULL_APK installation options */
    @Incubating
    fun installOptions(option: String)

    /** Sets the list of FULL_APK installation options */
    @Incubating
    fun installOptions(vararg options: String)
}