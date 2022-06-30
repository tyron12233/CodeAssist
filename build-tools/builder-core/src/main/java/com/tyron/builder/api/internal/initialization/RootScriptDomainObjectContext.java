package com.tyron.builder.api.internal.initialization;

import com.tyron.builder.api.internal.DomainObjectContext;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.internal.model.CalculatedModelValue;
import com.tyron.builder.internal.model.ModelContainer;
import com.tyron.builder.util.Path;

import javax.annotation.Nullable;
import java.util.function.Consumer;
import java.util.function.Function;

public class RootScriptDomainObjectContext implements DomainObjectContext, ModelContainer<Object> {
    private static final Object MODEL = new Object();
    public static final DomainObjectContext INSTANCE = new RootScriptDomainObjectContext();

    public static final DomainObjectContext PLUGINS = new RootScriptDomainObjectContext() {
        @Override
        public boolean isPluginContext() {
            return true;
        }
    };

    private RootScriptDomainObjectContext() {
    }

    @Override
    public Path identityPath(String name) {
        return Path.path(name);
    }

    @Override
    public Path projectPath(String name) {
        return Path.path(name);
    }

    @Override
    public Path getProjectPath() {
        return null;
    }

    @Nullable
    @Override
    public ProjectInternal getProject() {
        return null;
    }

    @Override
    public ModelContainer<Object> getModel() {
        return this;
    }

    @Override
    public boolean hasMutableState() {
        return true;
    }

    @Override
    public <S> S fromMutableState(Function<? super Object, ? extends S> factory) {
        return factory.apply(MODEL);
    }

    @Override
    public <S> S forceAccessToMutableState(Function<? super Object, ? extends S> factory) {
        return factory.apply(MODEL);
    }

    @Override
    public void applyToMutableState(Consumer<? super Object> action) {
        action.accept(MODEL);
    }

    @Override
    public Path getBuildPath() {
        return Path.ROOT;
    }

    @Override
    public boolean isScript() {
        return true;
    }

    @Override
    public boolean isRootScript() {
        return true;
    }

    @Override
    public boolean isPluginContext() {
        return false;
    }

    @Override
    public <T> CalculatedModelValue<T> newCalculatedValue(@Nullable T initialValue) {
        return new CalculatedModelValueImpl<>(initialValue);
    }

    private static class CalculatedModelValueImpl<T> implements CalculatedModelValue<T> {
        private volatile T value;

        CalculatedModelValueImpl(@Nullable T initialValue) {
            value = initialValue;
        }

        @Override
        public T get() throws IllegalStateException {
            T currentValue = getOrNull();
            if (currentValue == null) {
                throw new IllegalStateException("No value is available.");
            }
            return currentValue;
        }

        @Override
        public T getOrNull() {
            return value;
        }

        @Override
        public void set(T newValue) {
            value = newValue;
        }

        @Override
        public T update(Function<T, T> updateFunction) {
            synchronized (this) {
                T newValue = updateFunction.apply(value);
                value = newValue;
                return newValue;
            }
        }
    }
}
