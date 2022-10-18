package org.gradle.internal.io;

import org.gradle.api.Transformer;
import org.gradle.internal.concurrent.CompositeStoppable;

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
