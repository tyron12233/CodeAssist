package com.tyron.builder.api.internal.concurrent;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * A {@link Stoppable} that stops a collection of things. If an element implements
 * {@link java.io.Closeable} or {@link Stoppable} then the appropriate close/stop
 * method is called on that object, otherwise the element is ignored. Elements may be {@code null}, in which case they
 * are ignored.
 *
 * <p>Attempts to stop as many elements as possible in the presence of failures.</p>
 */
public class CompositeStoppable implements Stoppable {
    private static final Logger LOGGER = Logger.getLogger("CompositeStoppable");
    public static final Stoppable NO_OP_STOPPABLE = new Stoppable() {
        @Override
        public void stop() {
        }
    };
    private final List<Stoppable> elements = new ArrayList<Stoppable>();

    public CompositeStoppable() {
    }

    public static CompositeStoppable stoppable(Object... elements) {
        return new CompositeStoppable().add(elements);
    }

    public static CompositeStoppable stoppable(Iterable<?> elements) {
        return new CompositeStoppable().add(elements);
    }

    public CompositeStoppable add(Iterable<?> elements) {
        for (Object closeable : elements) {
            add(closeable);
        }
        return this;
    }

    public CompositeStoppable add(Object... elements) {
        for (Object closeable : elements) {
            add(closeable);
        }
        return this;
    }

    public synchronized CompositeStoppable add(Object closeable) {
        this.elements.add(toStoppable(closeable));
        return this;
    }

    private static Stoppable toStoppable(final Object object) {
        if (object instanceof Stoppable) {
            return (Stoppable) object;
        }
        if (object instanceof Closeable) {
            final Closeable closeable = (Closeable) object;
            return new Stoppable() {
                @Override
                public String toString() {
                    return closeable.toString();
                }

                @Override
                public void stop() {
                    try {
                        closeable.close();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            };
        }
        return NO_OP_STOPPABLE;
    }

    @Override
    public synchronized void stop() {
        Throwable failure = null;
        try {
            for (Stoppable element : elements) {
                try {
                    element.stop();
                } catch (Throwable throwable) {
                    if (failure == null) {
                        failure = throwable;
                    } else if (!Thread.currentThread().isInterrupted()) {
                        LOGGER.severe(String.format("Could not stop %s.", element) + "\n" + throwable);
                    }
                }
            }
        } finally {
            elements.clear();
        }

        if (failure != null) {
            throw new RuntimeException(failure);
        }
    }
}