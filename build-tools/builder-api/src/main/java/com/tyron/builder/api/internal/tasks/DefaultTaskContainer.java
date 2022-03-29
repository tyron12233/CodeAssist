package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.DefaultTask;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.providers.Provider;
import com.tyron.builder.api.tasks.DefaultTaskProvider;
import com.tyron.builder.api.tasks.TaskProvider;
import com.tyron.builder.api.tasks.TaskResolver;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public class DefaultTaskContainer implements TaskContainerInternal {

    private final Map<String, Task> nameToTask;
    private final List<Task> tasks;

    private final ProjectInternal project;
    public DefaultTaskContainer(ProjectInternal project) {
        this.project = project;
        nameToTask = new HashMap<>();
        tasks = new ArrayList<>();
    }

    @Override
    public TaskProvider<Task> register(String name, Action<? super Task> configurationAction) {
        TaskProvider<?> register = register(name, DefaultTask.class, configurationAction);
        //noinspection unchecked
        return ((TaskProvider<Task>) register);
    }

    @Override
    public <T extends Task> TaskProvider<T> register(String name,
                                                     Class<T> type,
                                                     Action<? super T> configurationAction) {
        return registerTask(name, type, configurationAction, new Class[]{ProjectInternal.class}, project);
    }

    @Override
    public <T extends Task> TaskProvider<T> register(String name, Class<T> type) {
        return registerTask(name, type, null, new Class[]{ProjectInternal.class}, project);
    }

    private <T extends Task> TaskProvider<T> registerTask(String name, Class<T> type, @Nullable final Action<? super T> configurationAction, Class<?>[] types, Object... constructorArgs) {

        DefaultTaskProvider<T> provider = new DefaultTaskProvider<>(name, type, types, constructorArgs);
        T t = provider.get();
        if (t != null) {
            t.setName(name);
            tasks.add(t);
            nameToTask.put(name, t);
            if (configurationAction != null) {
                configurationAction.execute(t);
            }
        }
        return provider;
    }

    @Override
    public Task resolveTask(String path) {
        return nameToTask.get(path);
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public boolean contains(Object o) {
        return false;
    }

    @NotNull
    @Override
    public Iterator<Task> iterator() {
        return null;
    }

    @NotNull
    @Override
    public Object[] toArray() {
        return new Object[0];
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

    }

    public class TaskCreatingProvider<I extends Task> implements TaskProvider<I> {
        private final Object[] constructorArgs;

        public TaskCreatingProvider(@Nullable Action<? super I> configureAction, Object... constructorArgs) {
            this.constructorArgs = constructorArgs;
        }

        @Override
        public I get() {
            return null;
        }

        @javax.annotation.Nullable
        @Override
        public I getOrNull() {
            return null;
        }

        @Override
        public I getOrElse(I defaultValue) {
            return null;
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
            return null;
        }
    }
}
