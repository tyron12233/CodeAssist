package com.tyron.builder.model.internal.core;

import com.tyron.builder.model.internal.type.ModelType;

import java.util.List;

public abstract class ModelViews {

    public static <T> ModelView<T> assertType(ModelView<?> untypedView, ModelType<T> type) {
        if (type.isAssignableFrom(untypedView.getType())) {
            @SuppressWarnings("unchecked") ModelView<T> view = (ModelView<T>) untypedView;
            return view;
        } else {
            // TODO better exception type
            throw new IllegalArgumentException("Model view of type " + untypedView.getType() + " requested as " + type);
        }
    }

    public static <T> ModelView<T> assertType(ModelView<?> untypedView, Class<T> type) {
        return assertType(untypedView, ModelType.of(type));
    }

    public static <T> ModelView<T> assertType(ModelView<?> untypedView, ModelReference<T> reference) {
        return assertType(untypedView, reference.getType());
    }

    public static <T> T getInstance(ModelView<?> untypedView, ModelReference<T> reference) {
        return assertType(untypedView, reference.getType()).getInstance();
    }

    public static <T> T getInstance(ModelView<?> untypedView, ModelType<T> type) {
        return assertType(untypedView, type).getInstance();
    }

    public static <T> T getInstance(ModelView<?> untypedView, Class<T> type) {
        return assertType(untypedView, ModelType.of(type)).getInstance();
    }

    public static <T> T getInstance(List<? extends ModelView<?>> views, int index, ModelType<T> type) {
        return getInstance(views.get(index), type);
    }

    public static <T> T getInstance(List<? extends ModelView<?>> views, int index, Class<T> type) {
        return getInstance(views.get(index), type);
    }
}
