package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.SyncIssue
import com.tyron.builder.model.v2.models.ProjectSyncIssues
import java.io.Serializable

/**
 * Implementation of [ProjectSyncIssues] for serialization via the Tooling API.
 */
data class ProjectSyncIssuesImpl(
    override val syncIssues: Collection<SyncIssue>
) : ProjectSyncIssues, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
