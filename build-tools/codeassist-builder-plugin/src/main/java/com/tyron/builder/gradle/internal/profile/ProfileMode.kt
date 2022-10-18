package com.tyron.builder.gradle.internal.profile

import com.android.utils.HelpfulEnumConverter

enum class ProfilingMode(
    val modeName: String?,
    val isDebuggable: Boolean?,
    val isProfileable: Boolean?
) {

    UNDEFINED(null, null, null),
    DEBUGGABLE("debuggable", true, false),
    PROFILEABLE("profileable", false, true);

    companion object {

        private val profilingModeConverter = HelpfulEnumConverter(ProfilingMode::class.java)

        fun getProfilingModeType(modeName: String?): ProfilingMode {
            return profilingModeConverter.convert(modeName) ?: UNDEFINED
        }
    }
}
