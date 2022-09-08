package com.tyron.builder.gradle.internal.ide

import com.tyron.builder.model.ProjectSyncIssues
import com.tyron.builder.model.SyncIssue
import java.io.Serializable

data class DefaultProjectSyncIssues(
    private val issues: Collection<SyncIssue>) : ProjectSyncIssues, Serializable {

    companion object {
        // Increase the value when adding/removing fields or when changing the
        // serialization/deserialization mechanism.
        private const val serialVersionUID: Long = 1L
    }

    override fun getSyncIssues() = issues
}
