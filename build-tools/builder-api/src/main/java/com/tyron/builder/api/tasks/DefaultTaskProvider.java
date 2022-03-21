package com.tyron.builder.api.tasks;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.providers.Provider;

import java.lang.reflect.Constructor;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

public class DefaultTaskProvider<T extends Task> implements TaskProvider<T> {

    private final Class<T> type;
    private final Class<?>[] constructorClasses;
    private final Object[] constructorArgs;
    private final String name;

    private T value;
    private boolean computed;

    public DefaultTaskProvider(String name, Class<T> type, Class<?>[] constructorClasses, Object[] constructorArgs) {
        this.name = name;
        this.type = type;
        this.constructorClasses = constructorClasses;
        this.constructorArgs = constructorArgs;
    }

    @Override
    public T get() {
        if (computed) {
            return value;
        }

        try {
            Constructor<T> constructor = type.getConstructor(constructorClasses);
            value = constructor.newInstance(constructorArgs);
        } catch (ReflectiveOperationException e) {
            value = null;
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

    @Override
    public <S> Provider<S> map(Transformer<? extends S, ? super T> transformer) {
        return null;
    }

    @Override
    public <S> Provider<S> flatMap(Transformer<? extends Provider<? extends S>, ? super T> transformer) {
        return null;
    }

    @Override
    public boolean isPresent() {
        return true;
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
        return name;
    }
}
