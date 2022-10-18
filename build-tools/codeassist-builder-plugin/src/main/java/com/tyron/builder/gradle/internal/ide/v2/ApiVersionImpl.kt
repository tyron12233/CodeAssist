package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.ApiVersion
import java.io.Serializable

data class ApiVersionImpl(
    override val apiLevel: Int,
    override val codename: String?
): ApiVersion, Serializable {

    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}

