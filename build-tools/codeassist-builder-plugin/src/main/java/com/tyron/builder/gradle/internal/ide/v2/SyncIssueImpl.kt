package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.SyncIssue
import java.io.Serializable

/**
 * Implementation of [SyncIssue] for serialization via the Tooling API.
 */
data class SyncIssueImpl(
    override val severity: Int,
    override val type: Int,
    override val data: String?,
    override val message: String,
    override val multiLineMessage: List<String?>?
) : SyncIssue, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
