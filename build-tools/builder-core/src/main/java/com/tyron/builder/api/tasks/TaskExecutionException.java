package com.tyron.builder.api.tasks;

import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.internal.exceptions.Contextual;

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
