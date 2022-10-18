package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.TestInfo
import java.io.File
import java.io.Serializable

/**
 * Implementation of [TestInfo] for serialization via the Tooling API.
 */
data class TestInfoImpl(
    override val animationsDisabled: Boolean,
    override val execution: TestInfo.Execution?,
    override val additionalRuntimeApks: Collection<File>,
    override val instrumentedTestTaskName: String
) : TestInfo, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
