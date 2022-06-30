package com.tyron.builder.api.internal.collections;

import com.tyron.builder.api.Action;
import com.tyron.builder.util.Predicates;

import java.util.function.Predicate;

public class CollectionFilter<T> implements Predicate<Object> {

    private Class<? extends T> type;
    private Predicate<? super T> spec;

    public CollectionFilter(Class<T> type) {
        this(type, Predicates.satisfyAll());
    }

    public CollectionFilter(Class<? extends T> type, Predicate<? super T> spec) {
        this.type = type;
        this.spec = spec;
    }

    public Class<? extends T> getType() {
        return type;
    }

    public T filter(Object object) {
        if (!type.isInstance(object)) {
            return null;
        }

        T t = type.cast(object);
        if (spec.test(t)) {
            return t;
        } else {
            return null;
        }
    }

    public Action<Object> filtered(final Action<? super T> action) {
        return new Action<Object>() {
            @Override
            public void execute(Object o) {
                T t = filter(o);
                if (t != null) {
                    action.execute(t);
                }
            }
        };
    }

    @Override
    public boolean test(Object element) {
        return filter(element) != null;
    }

    @SuppressWarnings("unchecked")
    public <S extends T> CollectionFilter<S> and(CollectionFilter<S> other) {
        return new CollectionFilter<S>(other.type, Predicates.intersect(spec, other.spec));
    }
}
