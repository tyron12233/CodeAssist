package com.tyron.builder.api.internal.tasks;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.DefaultTask;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.Transformer;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.api.internal.collections.ElementSource;
import com.tyron.builder.api.internal.collections.ListElementSource;
import com.tyron.builder.api.internal.exceptions.Contextual;
import com.tyron.builder.api.internal.operations.BuildOperationContext;
import com.tyron.builder.api.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.api.internal.operations.BuildOperationExecutor;
import com.tyron.builder.api.internal.operations.CallableBuildOperation;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.taskfactory.TaskIdentity;
import com.tyron.builder.api.internal.provider.ProviderInternal;
import com.tyron.builder.api.internal.provider.Providers;
import com.tyron.builder.api.project.BuildProject;
import com.tyron.builder.api.providers.Provider;
import com.tyron.builder.api.tasks.DefaultTaskProvider;
import com.tyron.builder.api.tasks.TaskProvider;
import com.tyron.builder.api.tasks.TaskResolver;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

public class DefaultTaskContainer implements TaskContainerInternal {

    private static final Object[] NO_ARGS = new Object[0];
    public final static String EAGERLY_CREATE_LAZY_TASKS_PROPERTY = "org.gradle.internal.tasks.eager";

    private static final Set<String> VALID_TASK_ARGUMENTS = ImmutableSet.of(
            Task.TASK_ACTION, Task.TASK_DEPENDS_ON, Task.TASK_DESCRIPTION, Task.TASK_GROUP, Task.TASK_NAME, Task.TASK_OVERWRITE, Task.TASK_TYPE, Task.TASK_CONSTRUCTOR_ARGS
    );
    private static final Set<String> MANDATORY_TASK_ARGUMENTS = ImmutableSet.of(
            Task.TASK_NAME, Task.TASK_TYPE
    );

//    private MutableModelNode modelNode;
    private final Map<String, Task> nameToTask;
    private final ElementSource<Task> tasks;
    private final boolean eagerlyCreateLazyTasks = true;


    private final ProjectInternal project;
    private final BuildOperationExecutor buildOperationExecutor;

    public DefaultTaskContainer(
            ProjectInternal project,
            BuildOperationExecutor buildOperationExecutor
    ) {
        this.project = project;
        this.buildOperationExecutor = buildOperationExecutor;
        nameToTask = new HashMap<>();
        tasks = new ListElementSource<>();
    }

    private static Object[] getConstructorArgs(Map<String, ?> args) {
        Object constructorArgs = args.get(Task.TASK_CONSTRUCTOR_ARGS);
        if (constructorArgs instanceof List) {
            List<?> asList = (List<?>) constructorArgs;
            return asList.toArray(new Object[0]);
        }
        if (constructorArgs instanceof Object[]) {
            return (Object[]) constructorArgs;
        }
        if (constructorArgs != null) {
            throw new IllegalArgumentException(String.format("%s must be a List or Object[].  Received %s", Task.TASK_CONSTRUCTOR_ARGS, constructorArgs.getClass()));
        }
        return NO_ARGS;
    }

    private static Map<String, ?> checkTaskArgsAndCreateDefaultValues(Map<String, ?> args) {
        validateArgs(args);
        if (!args.keySet().containsAll(MANDATORY_TASK_ARGUMENTS)) {
            Map<String, Object> argsWithDefaults = Maps.newHashMap(args);
            setIfNull(argsWithDefaults, Task.TASK_NAME, "");
            setIfNull(argsWithDefaults, Task.TASK_TYPE, DefaultTask.class);
            return argsWithDefaults;
        }
        return args;
    }

    private static void validateArgs(Map<String, ?> args) {
        if (!VALID_TASK_ARGUMENTS.containsAll(args.keySet())) {
            Map<String, Object> unknownArguments = new HashMap<String, Object>(args);
            unknownArguments.keySet().removeAll(VALID_TASK_ARGUMENTS);
            throw new InvalidUserDataException(String.format("Could not create task '%s': Unknown argument(s) in task definition: %s",
                    args.get(Task.TASK_NAME), unknownArguments.keySet()));
        }
    }

    private static void setIfNull(Map<String, Object> map, String key, Object defaultValue) {
        map.putIfAbsent(key, defaultValue);
    }

    @Override
    public TaskProvider<Task> register(String name, Action<? super Task> configurationAction) throws InvalidUserDataException {
        assertMutable("register(String, Action)");
        return Cast.uncheckedCast(register(name, DefaultTask.class, configurationAction));
    }

    @Override
    public <T extends Task> TaskProvider<T> register(String name, Class<T> type, Action<? super T> configurationAction) throws InvalidUserDataException {
        assertMutable("register(String, Class, Action)");
        return registerTask(name, type, configurationAction, NO_ARGS);
    }

    @Override
    public <T extends Task> TaskProvider<T> register(String name, Class<T> type) throws InvalidUserDataException {
        assertMutable("register(String, Class)");
        return register(name, type, NO_ARGS);
    }

    @Override
    public <T extends Task> TaskProvider<T> register(String name) throws InvalidUserDataException {
        assertMutable("register(String)");
        return Cast.uncheckedCast(register(name, DefaultTask.class));
    }

    @Override
    public <T extends Task> TaskProvider<T> register(String name, Class<T> type, Object... constructorArgs) {
        assertMutable("register(String, Class, Object...)");
        return registerTask(name, type, null, constructorArgs);
    }

    private void assertMutable(String s) {

    }

    private <T extends Task> TaskProvider<T> registerTask(final String name, final Class<T> type, @Nullable final Action<? super T> configurationAction, final Object... constructorArgs) {
        if (hasWithName(name)) {
            failOnDuplicateTask(name);
        }

        final TaskIdentity<T> identity = TaskIdentity.create(name, type, project);
        
        TaskProvider<T> provider = buildOperationExecutor.call(new CallableBuildOperation<TaskProvider<T>>() {
            @Override
            public TaskProvider<T> call(BuildOperationContext context) {
                TaskProvider<T> provider = new DefaultTaskProvider<>(project, identity, configurationAction, type, constructorArgs);
                addLaterInternal(provider);
                context.setResult(REGISTER_RESULT);
                return provider;
            }

            @Override
            public BuildOperationDescriptor.Builder description() {
                return registerDescriptor(identity);
            }
        });

        if (eagerlyCreateLazyTasks) {
            T t = provider.get();

            if (configurationAction != null) {
                configurationAction.execute(t);
            }
        }

        return provider;
    }

    private <T extends Task> void addLaterInternal(Provider<T> provider) {
        ProviderInternal<T> providerInternal = Providers.internal(provider);
        tasks.addPending(providerInternal);
    }


    public boolean hasWithName(String name) {
        for (Task task : tasks) {
            if (name.equals(task.getName())) {
                return true;
            }
        }
        return false;
    }

    private void failOnDuplicateTask(String task) {
        throw new DuplicateTaskException(String.format("Cannot add task '%s' as a task with that name already exists.", task));
    }


    @Override
    public Task resolveTask(String path) {
        if (Strings.isNullOrEmpty(path)) {
            throw new InvalidUserDataException("A path must be specified!");
        }
        return getByPath(path);
    }

    public Task getByPath(String path) {
        Task task = findByPath(path);
        if (task == null) {
            throw new BuildException(String.format("Task with path '%s' not found in %s.", path, project));
        }
        return task;
    }

    public Task findByPath(String path) {
        if (Strings.isNullOrEmpty(path)) {
            throw new InvalidUserDataException("A path must be specified!");
        }
        if (!path.contains(BuildProject.PATH_SEPARATOR)) {
            return findByName(path);
        }

        String projectPath = StringUtils.substringBeforeLast(path, BuildProject.PATH_SEPARATOR);
        ProjectInternal project = this.project.findProject(Strings.isNullOrEmpty(projectPath) ? BuildProject.PATH_SEPARATOR : projectPath);
        if (project == null) {
            return null;
        }
        project.getOwner().ensureTasksDiscovered();


        return project.getTasks().findByName(StringUtils.substringAfterLast(path, BuildProject.PATH_SEPARATOR));
    }

    /**
     * @return true if this method _may_ have done some work.
     */
    private boolean maybeCreateTasks(String name) {
        tasks.realizePending();
        return true;
    }



    @Override   
    public Task findByName(String name) {
//        Task task = super.findByName(name);
//        if (task != null) {
//            return task;
//        }
        if (!maybeCreateTasks(name)) {
            return null;
        }
//        return super.findByNameWithoutRules(name);

        for (Task task : tasks) {
            if (name.equals(task.getName())) {
                return task;
            }
        }

        return null;
    }


    @Override
    public int size() {
        return tasks.size();
    }

    @Override
    public boolean isEmpty() {
        return tasks.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return tasks.contains(o);
    }

    @NotNull
    @Override
    public Iterator<Task> iterator() {
        return tasks.iterator();
    }

    @NotNull
    @Override
    public Object[] toArray() {
        return null;
    }

    @NotNull
    @Override
    public <T> T[] toArray(@NotNull T[] ts) {
        return null;
    }

    @Override
    public boolean add(Task task) {
        return false;
    }

    @Override
    public boolean remove(Object o) {
        return false;
    }

    @Override
    public boolean containsAll(@NotNull Collection<?> collection) {
        return false;
    }

    @Override
    public boolean addAll(@NotNull Collection<? extends Task> collection) {
        return false;
    }

    @Override
    public boolean removeAll(@NotNull Collection<?> collection) {
        return false;
    }

    @Override
    public boolean retainAll(@NotNull Collection<?> collection) {
        return false;
    }

    @Override
    public void clear() {

    }

    @Override
    public void discoverTasks() {
        // fire deferred configuration();
    }

    @Override
    public void prepareForExecution(Task task) {
        assert task.getProject() == project;
    }

    private static BuildOperationDescriptor.Builder realizeDescriptor(TaskIdentity<?> identity, boolean replacement, boolean eager) {
        return BuildOperationDescriptor.displayName("Realize task " + identity.identityPath)
                .details(new RealizeDetails(identity, replacement, eager));
    }

    private static BuildOperationDescriptor.Builder registerDescriptor(TaskIdentity<?> identity) {
        return BuildOperationDescriptor.displayName("Register task " + identity.identityPath)
                .details(new RegisterDetails(identity));
    }

    public class TaskCreatingProvider<I extends Task> implements TaskProvider<I> {

        private final TaskIdentity<I> identity;
        private final Object[] constructorArgs;
        @Nullable
        private Action<? super I> configureAction;

        public TaskCreatingProvider(TaskIdentity<I> identity, @Nullable Action<? super I> configureAction, Object... constructorArgs) {
            this.configureAction = configureAction;
//            super(identity.name, identity.type, configureAction);
            this.identity = identity;
            this.constructorArgs = constructorArgs;
//            statistics.lazyTask();
        }

        @Override
        public I get() {
            return null;
        }

        @javax.annotation.Nullable
        @Override
        public I getOrNull() {
            return get();
        }

        @Override
        public I getOrElse(I defaultValue) {
            I i = get();
            if (i != null) {
                return i;
            }
            return defaultValue;
        }

        @Override
        public <S> Provider<S> map(Transformer<? extends S, ? super I> transformer) {
            return null;
        }

        @Override
        public <S> Provider<S> flatMap(Transformer<? extends Provider<? extends S>, ? super I> transformer) {
            return null;
        }

        @Override
        public boolean isPresent() {
            return false;
        }

        @Override
        public Provider<I> orElse(I value) {
            return null;
        }

        @Override
        public Provider<I> orElse(Provider<? extends I> provider) {
            return null;
        }

        @Override
        public Provider<I> forUseAtConfigurationTime() {
            return null;
        }

        @Override
        public <U, R> Provider<R> zip(Provider<U> right,
                                      BiFunction<? super I, ? super U, ? extends R> combiner) {
            return null;
        }

        @Override
        public void configure(Action<? super I> action) {

        }

        @Override
        public String getName() {
            return identity.name;
        }
    }

    private static final RegisterTaskBuildOperationType.Result REGISTER_RESULT = new RegisterTaskBuildOperationType.Result() {
    };
    private static final RealizeTaskBuildOperationType.Result REALIZE_RESULT = new RealizeTaskBuildOperationType.Result() {
    };

    private static class DuplicateTaskException extends InvalidUserDataException {
        public DuplicateTaskException(String message) {
            super(message);
        }
    }

    @Contextual
    private static class IncompatibleTaskTypeException extends InvalidUserDataException {
        public IncompatibleTaskTypeException(String message) {
            super(message);
        }
    }

    private static final class RealizeDetails implements RealizeTaskBuildOperationType.Details {

        private final TaskIdentity<?> identity;
        private final boolean replacement;
        private final boolean eager;

        RealizeDetails(TaskIdentity<?> identity, boolean replacement, boolean eager) {
            this.identity = identity;
            this.replacement = replacement;
            this.eager = eager;
        }

        @Override
        public String getBuildPath() {
            return identity.buildPath.toString();
        }

        @Override
        public String getTaskPath() {
            return identity.projectPath.toString();
        }

        @Override
        public long getTaskId() {
            return identity.uniqueId;
        }

        @Override
        public boolean isReplacement() {
            return replacement;
        }

        @Override
        public boolean isEager() {
            return eager;
        }

    }

    private static final class RegisterDetails implements RegisterTaskBuildOperationType.Details {

        private final TaskIdentity<?> identity;

        RegisterDetails(TaskIdentity<?> identity) {
            this.identity = identity;
        }

        @Override
        public String getBuildPath() {
            return identity.buildPath.toString();
        }

        @Override
        public String getTaskPath() {
            return identity.projectPath.toString();
        }

        @Override
        public long getTaskId() {
            return identity.uniqueId;
        }

        @Override
        public boolean isReplacement() {
            return false;
        }

    }
}
