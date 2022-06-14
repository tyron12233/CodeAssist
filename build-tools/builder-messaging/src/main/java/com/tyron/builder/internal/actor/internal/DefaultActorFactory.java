package com.tyron.builder.internal.actor.internal;

import com.tyron.builder.internal.actor.Actor;
import com.tyron.builder.internal.actor.ActorFactory;
import com.tyron.builder.internal.concurrent.*;
import com.tyron.builder.internal.dispatch.*;
import org.slf4j.LoggerFactory;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * A basic {@link ActorFactory} implementation. Currently cannot support creating both a blocking and non-blocking actor for the same target object.
 */
public class DefaultActorFactory implements ActorFactory, Stoppable {
    private final Map<Object, NonBlockingActor> nonBlockingActors = new IdentityHashMap<Object, NonBlockingActor>();
    private final Map<Object, BlockingActor> blockingActors = new IdentityHashMap<Object, BlockingActor>();
    private final Object lock = new Object();
    private final ExecutorFactory executorFactory;

    public DefaultActorFactory(ExecutorFactory executorFactory) {
        this.executorFactory = executorFactory;
    }

    /**
     * Stops all actors.
     */
    @Override
    public void stop() {
        synchronized (lock) {
            try {
                CompositeStoppable.stoppable(nonBlockingActors.values()).add(blockingActors.values()).stop();
            } finally {
                nonBlockingActors.clear();
            }
        }
    }

    @Override
    public Actor createActor(Object target) {
        if (target instanceof NonBlockingActor) {
            return (NonBlockingActor) target;
        }
        synchronized (lock) {
            if (blockingActors.containsKey(target)) {
                throw new UnsupportedOperationException("Cannot create a non-blocking and blocking actor for the same object. This is not implemented yet.");
            }
            NonBlockingActor actor = nonBlockingActors.get(target);
            if (actor == null) {
                actor = new NonBlockingActor(target);
                nonBlockingActors.put(target, actor);
            }
            return actor;
        }
    }

    @Override
    public Actor createBlockingActor(Object target) {
        synchronized (lock) {
            if (nonBlockingActors.containsKey(target)) {
                throw new UnsupportedOperationException("Cannot create a non-blocking and blocking actor for the same object. This is not implemented yet.");
            }
            BlockingActor actor = blockingActors.get(target);
            if (actor == null) {
                actor = new BlockingActor(target);
                blockingActors.put(target, actor);
            }
            return actor;
        }
    }

    private void stopped(NonBlockingActor actor) {
        synchronized (lock) {
            nonBlockingActors.values().remove(actor);
        }
    }

    private void stopped(BlockingActor actor) {
        synchronized (lock) {
            blockingActors.values().remove(actor);
        }
    }

    private class BlockingActor implements Actor {
        private final Dispatch<MethodInvocation> dispatch;
        private final Object lock = new Object();
        private boolean stopped;

        public BlockingActor(Object target) {
            dispatch = new ReflectionDispatch(target);
        }

        @Override
        public <T> T getProxy(Class<T> type) {
            return new ProxyDispatchAdapter<T>(this, type, ThreadSafe.class).getSource();
        }

        @Override
        public void stop() throws DispatchException {
            synchronized (lock) {
                stopped = true;
            }
            stopped(this);
        }

        @Override
        public void dispatch(MethodInvocation message) {
            synchronized (lock) {
                if (stopped) {
                    throw new IllegalStateException("This actor has been stopped.");
                }
                dispatch.dispatch(message);
            }
        }
    }

    private class NonBlockingActor implements Actor {
        private final Dispatch<MethodInvocation> dispatch;
        private final ManagedExecutor executor;
        private final ExceptionTrackingFailureHandler failureHandler;

        public NonBlockingActor(Object targetObject) {
            executor = executorFactory.create("Dispatch " + targetObject);
            failureHandler = new ExceptionTrackingFailureHandler(LoggerFactory.getLogger(NonBlockingActor.class));
            dispatch = new AsyncDispatch<MethodInvocation>(executor,
                    new FailureHandlingDispatch<MethodInvocation>(
                            new ReflectionDispatch(targetObject),
                            failureHandler), Integer.MAX_VALUE);
        }

        @Override
        public <T> T getProxy(Class<T> type) {
            return new ProxyDispatchAdapter<T>(this, type, ThreadSafe.class).getSource();
        }

        @Override
        public void stop() {
            try {
                CompositeStoppable.stoppable(dispatch, executor, failureHandler).stop();
            } finally {
                stopped(this);
            }
        }

        @Override
        public void dispatch(MethodInvocation message) {
            dispatch.dispatch(message);
        }
    }
}
