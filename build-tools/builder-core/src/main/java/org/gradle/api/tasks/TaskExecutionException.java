package org.gradle.api.tasks;

import org.gradle.api.internal.TaskInternal;
import org.gradle.internal.exceptions.Contextual;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Contextual
public class TaskExecutionException extends RuntimeException {

    public TaskExecutionException(TaskInternal task, Throwable e) {
        super(e);
    }


    public Collection<? extends Throwable> getCauses() {
        return Collections.emptyList();
    }

    public void initCauses(List<Throwable> causes) {

    }
}
