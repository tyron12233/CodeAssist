package com.tyron.builder.model;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * Model for a project's {@link SyncIssue}s.
 *
 * <p>This model should be fetched last (after other models), in order to have all the SyncIssue's
 * collected and delivered.
 */
public interface ProjectSyncIssues {

    /** Returns issues found during sync. */
    @NotNull
    Collection<SyncIssue> getSyncIssues();
}