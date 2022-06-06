package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.internal.AbstractTask;
import com.tyron.builder.api.internal.project.taskfactory.ITaskFactory;
import com.tyron.builder.api.internal.project.taskfactory.TaskInstantiator;
import com.tyron.builder.api.tasks.TaskProvider;
import com.tyron.builder.internal.DisplayName;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.taskfactory.TaskIdentity;
import com.tyron.builder.api.internal.provider.ProviderInternal;
import com.tyron.builder.api.internal.provider.ValueSanitizer;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.api.provider.Provider;

import java.lang.reflect.Constructor;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

public class DefaultTaskProvider<T extends Task> implements TaskProvider<T>, ProviderInternal<T> {

    private Action<? super T> configurationAction;
    private final Class<T> type;
    private final Object[] constructorArgs;
    private ProjectInternal project;
    private ITaskFactory taskInstantiator;
    private TaskIdentity<T> taskIdentity;
    private T value;
    private boolean computed;

    public DefaultTaskProvider(ProjectInternal project, ITaskFactory taskInstantiator, TaskIdentity<T> taskIdentity, Action<? super T> configurationAction, Class<T> type, Object[] constructorArgs) {
        this.project = project;
        this.taskInstantiator = taskInstantiator;
        this.taskIdentity = taskIdentity;
        this.configurationAction = configurationAction;
        this.type = type;
        this.constructorArgs = constructorArgs;
    }

    @Override
    public T get() {
        if (computed) {
            return value;
        }

        try {
            value = taskInstantiator.create(taskIdentity, constructorArgs);
        } catch (Throwable e) {
            throw UncheckedException.throwAsUncheckedException(e);
        } finally {
            computed = true;
        }
        return value;
    }

    @Nullable
    @Override
    public T getOrNull() {
        return get();
    }

    @Override
    public T getOrElse(T defaultValue) {
        T t = get();
        if (t == null) {
            return defaultValue;
        }
        return t;
    }

    @org.jetbrains.annotations.Nullable
    @Override
    public Class<T> getType() {
        return type;
    }

    @Override
    public <S> ProviderInternal<S> map(Transformer<? extends S, ? super T> transformer) {
        return null;
    }

    @Override
    public Value<? extends T> calculateValue(ValueConsumer consumer) {
        return Value.ofNullable(get());
    }

    @Override
    public ProviderInternal<T> asSupplier(DisplayName owner,
                                          Class<? super T> targetType,
                                          ValueSanitizer<? super T> sanitizer) {
        return null;
    }

    @Override
    public ProviderInternal<T> withFinalValue(ValueConsumer consumer) {
        return null;
    }

    @Override
    public ExecutionTimeValue<? extends T> calculateExecutionTimeValue() {
        return null;
    }

    @Override
    public <S> Provider<S> flatMap(Transformer<? extends Provider<? extends S>, ? super T> transformer) {
        return null;
    }

    @Override
    public boolean isPresent() {
        return computed && value != null;
    }

    @Override
    public Provider<T> orElse(T value) {
        return null;
    }

    @Override
    public Provider<T> orElse(Provider<? extends T> provider) {
        return null;
    }

    @Override
    public Provider<T> forUseAtConfigurationTime() {
        return null;
    }

    @Override
    public <U, R> Provider<R> zip(Provider<U> right,
                                  BiFunction<? super T, ? super U, ? extends R> combiner) {
        return null;
    }

    @Override
    public void configure(Action<? super T> action) {
        action.execute(get());
    }

    @Override
    public String getName() {
        return taskIdentity.name;
    }

    @Override
    public ValueProducer getProducer() {
        return ValueProducer.unknown();
    }

    @Override
    public boolean calculatePresence(ValueConsumer consumer) {
        return false;
    }

    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {

    }
}
