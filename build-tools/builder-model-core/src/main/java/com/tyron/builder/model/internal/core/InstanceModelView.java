package com.tyron.builder.model.internal.core;

import javax.annotation.concurrent.ThreadSafe;
import com.tyron.builder.api.Action;
import com.tyron.builder.internal.Actions;
import com.tyron.builder.model.internal.type.ModelType;

@ThreadSafe
public class InstanceModelView<T> implements ModelView<T> {

    private final ModelPath path;
    private final ModelType<T> type;
    private final T instance;
    private final Action<? super T> onClose;

    public InstanceModelView(ModelPath path, ModelType<T> type, T instance, Action<? super T> onClose) {
        this.path = path;
        this.type = type;
        this.instance = instance;
        this.onClose = onClose;
    }

    public static <T> ModelView<T> of(ModelPath path, ModelType<T> type, T instance) {
        return of(path, type, instance, Actions.doNothing());
    }

    public static <T> ModelView<T> of(ModelPath path, ModelType<T> type, T instance, Action<? super T> onClose) {
        return new InstanceModelView<T>(path, type, instance, onClose);
    }

    @Override
    public ModelPath getPath() {
        return path;
    }

    @Override
    public ModelType<T> getType() {
        return type;
    }

    @Override
    public T getInstance() {
        return instance;
    }

    @Override
    public void close() {
        onClose.execute(instance);
    }
}
