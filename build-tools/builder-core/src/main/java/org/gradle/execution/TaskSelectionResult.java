package org.gradle.execution;

import org.gradle.api.Task;

import java.util.Collection;

public interface TaskSelectionResult {
    void collectTasks(Collection<? super Task> tasks);
}
