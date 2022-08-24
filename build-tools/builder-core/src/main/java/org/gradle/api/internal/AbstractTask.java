package org.gradle.api.internal;

import static org.gradle.api.internal.lambdas.SerializableLambdas.factory;
import static org.gradle.util.GUtil.uncheckedCall;

import org.gradle.api.Action;
import org.gradle.api.Describable;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Task;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.project.taskfactory.TaskIdentity;
import org.gradle.api.internal.tasks.InputChangesAwareTaskAction;
import org.gradle.api.specs.Spec;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.Internal;
import org.gradle.internal.Factory;
import org.gradle.internal.execution.history.changes.InputChangesInternal;
import org.gradle.internal.hash.ClassLoaderHierarchyHasher;
import org.gradle.internal.scripts.ScriptOrigin;
import org.gradle.internal.serialization.Cached;
import org.gradle.internal.snapshot.impl.ImplementationSnapshot;
import org.gradle.util.Predicates;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

@DisableCachingByDefault(because = "Abstract super-class, not to be instantiated directly")
public abstract class AbstractTask implements TaskInternal {

    private static final ThreadLocal<TaskInfo> NEXT_INSTANCE = new ThreadLocal<TaskInfo>();

    private Spec<? super Task> onlyIf = Specs.satisfyAll();
    private String reasonNotToTrackState;
    private boolean impliesSubProjects;

    private String group;

    protected AbstractTask() {
        this(taskInfo());
    }

    protected AbstractTask(TaskInfo taskInfo) {

    }

    protected static TaskInfo taskInfo() {
        return NEXT_INSTANCE.get();
    }

    @Internal
    @Override
    public String getGroup() {
        return group;
    }

    @Override
    public void setGroup(String group) {
        this.group = group;
    }

    @Internal
    @Override
    public boolean getImpliesSubProjects() {
        return impliesSubProjects;
    }

    @Override
    public void setImpliesSubProjects(boolean impliesSubProjects) {
        this.impliesSubProjects = impliesSubProjects;
    }

    @Internal
    @Override
    public Spec<? super TaskInternal> getOnlyIf() {
        return onlyIf;
    }

    @Override
    public void doNotTrackState(String reasonNotToTrackState) {
        if (reasonNotToTrackState == null) {
            throw new InvalidUserDataException("notTrackingReason must not be null!");
        }
        this.reasonNotToTrackState = reasonNotToTrackState;
    }

    @Override
    public void setOnlyIf(Spec<? super Task> onlyIfSpec) {
        this.onlyIf = onlyIfSpec;
    }

    @Override
    public void onlyIf(Spec<? super Task> onlyIfSpec) {
        setOnlyIf(onlyIfSpec);
    }

    @Override
    public void prependParallelSafeAction(final Action<? super Task> action) {
        if (action == null) {
            throw new InvalidUserDataException("Action must not be null!");
        }
        getTaskActions().add(0, wrap(action));
    }

    @Override
    public Factory<File> getTemporaryDirFactory() {
        // Cached during serialization so it can be isolated from this task
        final Cached<File> temporaryDir = Cached.of(this::getTemporaryDir);
        return factory(temporaryDir::get);
    }

    protected InputChangesAwareTaskAction wrap(final Action<? super Task> action) {
        return wrap(action, "unnamed action");
    }

    protected InputChangesAwareTaskAction wrap(final Action<? super Task> action, String actionName) {
        if (action instanceof InputChangesAwareTaskAction) {
            return (InputChangesAwareTaskAction) action;
        }
        return new TaskActionWrapper(action, actionName);
    }

    private static class TaskActionWrapper implements InputChangesAwareTaskAction {
        private final Action<? super Task> action;
        private final String maybeActionName;

        /**
         * The <i>action name</i> is used to construct a human readable name for
         * the actions to be used in progress logging. It is only used if
         * the wrapped action does not already implement {@link Describable}.
         */
        public TaskActionWrapper(Action<? super Task> action, String maybeActionName) {
            this.action = action;
            this.maybeActionName = maybeActionName;
        }

        @Override
        public void setInputChanges(InputChangesInternal inputChanges) {
        }

        @Override
        public void clearInputChanges() {
        }

        @Override
        public void execute(Task task) {
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(action.getClass().getClassLoader());
            try {
                action.execute(task);
            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }
        }

        public ImplementationSnapshot getActionImplementation(ClassLoaderHierarchyHasher hasher) {
            return ImplementationSnapshot.of(getActionClassName(action), hasher.getClassLoaderHash(action.getClass().getClassLoader()));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TaskActionWrapper)) {
                return false;
            }

            TaskActionWrapper that = (TaskActionWrapper) o;
            return action.equals(that.action);
        }

        @Override
        public int hashCode() {
            return action.hashCode();
        }

        @Override
        public String getDisplayName() {
            if (action instanceof Describable) {
                return ((Describable) action).getDisplayName();
            }
            return "Execute " + maybeActionName;
        }
    }

    protected static String getActionClassName(Object action) {
        if (action instanceof ScriptOrigin) {
            ScriptOrigin origin = (ScriptOrigin) action;
            return origin.getOriginalClassName() + "_" + origin.getContentHash();
        } else {
            return action.getClass().getName();
        }
    }

    @Override
    public void appendParallelSafeAction(final Action<? super Task> action) {
        if (action == null) {
            throw new InvalidUserDataException("Action must not be null!");
        }
        getTaskActions().add(wrap(action));
    }

    protected static class TaskInfo {
        public final TaskIdentity<?> identity;
        public final ProjectInternal project;

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
