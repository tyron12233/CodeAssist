package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.internal.TaskInternal;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

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
