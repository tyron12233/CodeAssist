package com.tyron.builder.gradle.errors;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.errors.EvalIssueException;
import com.tyron.builder.model.SyncIssue;

import org.jetbrains.annotations.NotNull;

public class NoOpSyncIssueReporter extends SyncIssueReporter {
    @Override
    protected void reportIssue(@NotNull Type type,
                               @NotNull Severity severity,
                               @NotNull EvalIssueException exception) {

    }

    @Override
    public boolean hasIssue(@NotNull Type type) {
        return false;
    }

    @NotNull
    @Override
    public ImmutableList<SyncIssue> getSyncIssues() {
        return ImmutableList.of();
    }

    @Override
    public void lockHandler() {

    }
}
