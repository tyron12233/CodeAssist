package com.tyron.builder.internal.io;

import com.tyron.builder.api.Transformer;
import com.tyron.builder.internal.concurrent.CompositeStoppable;

import java.io.Closeable;

public abstract class IoUtils {

    // TODO merge in IoActions

    public static <T, C extends Closeable> T get(C resource, Transformer<T, ? super C> transformer) {
        try {
            return transformer.transform(resource);
        } finally {
            CompositeStoppable.stoppable(resource).stop();
        }
    }
}
