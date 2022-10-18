package org.gradle.api.internal;

import org.gradle.api.Action;

import java.util.function.Predicate;

import javax.annotation.Nullable;

public interface CollectionCallbackActionDecorator {
    <T> Action<T> decorate(@Nullable Action<T> action);

    <T> Predicate<T> decorateSpec(Predicate<T> spec);

    CollectionCallbackActionDecorator NOOP = new CollectionCallbackActionDecorator() {
        @Override
        public <T> Action<T> decorate(@Nullable Action<T> action) {
            return action;
        }

        @Override
        public <T> Predicate<T> decorateSpec(Predicate<T> spec) {
            return spec;
        }
    };
}
