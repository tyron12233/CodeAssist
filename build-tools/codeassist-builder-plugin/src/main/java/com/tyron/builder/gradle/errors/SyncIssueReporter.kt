package com.tyron.builder.gradle.errors

import com.google.common.collect.ImmutableList
import com.tyron.builder.errors.IssueReporter
import com.tyron.builder.model.SyncIssue

/**
 * Error reporter to be used during configuration only.
 *
 * This should not be used during tasks execution. Prefer [DefaultIssueReporter] inside tasks.
 */
abstract class SyncIssueReporter : IssueReporter() {

    abstract val syncIssues: ImmutableList<SyncIssue>

    /**
     * Lock this issue handler and if any issue is reported after this is called, the handler
     * will throw just like as like it's running in non-sync mode.
     */
    abstract fun lockHandler()
}