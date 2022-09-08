package com.tyron.builder.model.v2.models

import com.tyron.builder.model.v2.AndroidModel
import com.tyron.builder.model.v2.ide.SyncIssue

/**
 * Model for a project's [SyncIssue]s.
 *
 * This model should be fetched last (after other models), in order to have all the SyncIssue's
 * collected and delivered.
 */
interface ProjectSyncIssues: AndroidModel {
    /** Returns issues found during sync.  */
    val syncIssues: Collection<SyncIssue>
}
