package com.tyron.builder.api.internal.project.taskfactory;

import com.google.common.collect.ImmutableList;

import java.util.Optional;

public class TaskClassInfo {
    private final ImmutableList<TaskActionFactory> taskActionFactories;
    private final boolean cacheable;
    private final Optional<String> reasonNotToTrackState;

    public TaskClassInfo(ImmutableList<TaskActionFactory> taskActionFactories, boolean cacheable, Optional<String> reasonNotToTrackState) {
        this.taskActionFactories = taskActionFactories;
        this.cacheable = cacheable;
        this.reasonNotToTrackState = reasonNotToTrackState;
    }

    public ImmutableList<TaskActionFactory> getTaskActionFactories() {
        return taskActionFactories;
    }

    public boolean isCacheable() {
        return cacheable;
    }

    public Optional<String> getReasonNotToTrackState() {
        return reasonNotToTrackState;
    }
}
