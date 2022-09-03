package com.tyron.builder.gradle.internal.dsl

import com.google.common.collect.ImmutableList
import com.tyron.builder.api.dsl.Installation
import javax.inject.Inject

/** Options for the adb tool. */
/** Options for the adb tool. */
open class AdbOptions @Inject constructor() : com.tyron.builder.model.AdbOptions,
    com.tyron.builder.api.dsl.AdbOptions,
    Installation {

    override var timeOutInMs: Int = 0

    override var installOptions: Collection<String>? = null

    open fun timeOutInMs(timeOutInMs: Int) {
        this.timeOutInMs = timeOutInMs
    }

    fun setInstallOptions(option: String) {
        installOptions = ImmutableList.of(option)
    }

    fun setInstallOptions(vararg options: String) {
        installOptions = ImmutableList.copyOf(options)
    }

    override fun installOptions(option: String) {
        installOptions = ImmutableList.of(option)
    }

    override fun installOptions(vararg options: String) {
        installOptions = ImmutableList.copyOf(options)
    }
}