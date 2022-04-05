package com.tyron.builder.api;

import static com.tyron.builder.api.internal.GUtil.uncheckedCall;

import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.taskfactory.TaskIdentity;
import com.tyron.builder.api.util.Predicates;

import java.util.concurrent.Callable;
import java.util.function.Predicate;

public abstract class AbstractTask implements TaskInternal {

    private static final ThreadLocal<TaskInfo> NEXT_INSTANCE = new ThreadLocal<TaskInfo>();

    private Predicate<? super Task> onlyIf = Predicates.satisfyAll();

    protected AbstractTask() {
        this(taskInfo());
    }

    protected AbstractTask(TaskInfo taskInfo) {

    }

    static TaskInfo taskInfo() {
        return NEXT_INSTANCE.get();
    }

    @Override
    public boolean getImpliesSubProjects() {
        return false;
    }

    @Override
    public Predicate<? super TaskInternal> getOnlyIf() {
        return onlyIf;
    }

    @Override
    public void setOnlyIf(Predicate<? super Task> onlyIfSpec) {
        this.onlyIf = onlyIfSpec;
    }

    @Override
    public void onlyIf(Predicate<? super Task> onlyIfSpec) {
        setOnlyIf(onlyIfSpec);
    }

    static class TaskInfo {
        final TaskIdentity<?> identity;
        final ProjectInternal project;

        private TaskInfo(TaskIdentity<?> identity, ProjectInternal project) {
            this.identity = identity;
            this.project = project;
        }
    }

    public static <T extends Task> T injectIntoNewInstance(ProjectInternal project, TaskIdentity<T> identity, Callable<T> factory) {
        NEXT_INSTANCE.set(new TaskInfo(identity, project));
        try {
            return uncheckedCall(factory);
        } finally {
            NEXT_INSTANCE.set(null);
        }
    }
}
