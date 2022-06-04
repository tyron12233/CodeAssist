package com.tyron.builder.api.internal.file.copy;

import groovy.lang.Closure;
import groovy.lang.GString;
import com.tyron.builder.api.Transformer;

import java.util.ArrayList;
import java.util.List;

public class ChainingTransformer<T> implements Transformer<T, T> {
    private final List<Transformer<T, T>> transformers = new ArrayList<Transformer<T, T>>();
    private final Class<T> type;

    public ChainingTransformer(Class<T> type) {
        this.type = type;
    }

    @Override
    public T transform(T original) {
        T value = original;
        for (Transformer<T, T> transformer : transformers) {
            value = type.cast(transformer.transform(value));
        }
        return value;
    }

    public void add(Transformer<T, T> transformer) {
        transformers.add(transformer);
    }

    public void add(final Closure transformer) {
        transformers.add(new Transformer<T, T>() {
            @Override
            public T transform(T original) {
                transformer.setDelegate(original);
                transformer.setResolveStrategy(Closure.DELEGATE_FIRST);
                Object value = transformer.call(original);
                if (type.isInstance(value)) {
                    return type.cast(value);
                }
                if (type == String.class && value instanceof GString) {
                    return type.cast(value.toString());
                }
                return original;
            }
        });
    }

    public boolean hasTransformers() {
        return !transformers.isEmpty();
    }
}
