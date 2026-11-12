package dev.ide.jvm.standin.java.util.concurrent;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@code java.util.concurrent.StructuredTaskScope} as Java 21 previews it, for a runtime that has none. The VM
 * interprets this class under that name (see {@code PlatformStandIns}).
 *
 * <p>Each fork runs on a daemon thread of its own. The scope's owner forks, joins and closes; {@code join}
 * waits for every fork or for {@code shutdown}, which interrupts the forks still running.
 */
public class StructuredTaskScope<T> implements AutoCloseable {

    /** A forked subtask. */
    public interface Subtask<T> extends Supplier<T> {
        enum State { UNAVAILABLE, SUCCESS, FAILED }

        Callable<? extends T> task();

        State state();

        T get();

        Throwable exception();
    }

    private static final class SubtaskImpl<T> implements Subtask<T>, Runnable {
        private final StructuredTaskScope<? super T> scope;
        private final Callable<? extends T> task;
        private volatile State state = State.UNAVAILABLE;
        private volatile Object outcome;
        Thread thread;

        SubtaskImpl(StructuredTaskScope<? super T> scope, Callable<? extends T> task) {
            this.scope = scope;
            this.task = task;
        }

        @Override
        public void run() {
            T result = null;
            Throwable failure = null;
            try {
                result = task.call();
            } catch (Throwable e) {
                failure = e;
            }
            // A fork that finishes after the scope was shut down stays UNAVAILABLE, as on the platform.
            if (!scope.shutdown) {
                if (failure == null) {
                    outcome = result;
                    state = State.SUCCESS;
                } else {
                    outcome = failure;
                    state = State.FAILED;
                }
                scope.handleComplete(this);
            }
            scope.finished(this);
        }

        @Override
        public Callable<? extends T> task() {
            return task;
        }

        @Override
        public State state() {
            return state;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T get() {
            scope.ensureJoinedIfOwner();
            if (state != State.SUCCESS) throw new IllegalStateException("Subtask not completed or did not complete successfully");
            return (T) outcome;
        }

        @Override
        public Throwable exception() {
            scope.ensureJoinedIfOwner();
            if (state != State.FAILED) throw new IllegalStateException("Subtask not completed or did not complete with exception");
            return (Throwable) outcome;
        }

        /** The result, read on the fork's own thread from {@code handleComplete}, where no join is owed. */
        @SuppressWarnings("unchecked")
        T get0() {
            return (T) outcome;
        }

        Throwable failure0() {
            return (Throwable) outcome;
        }

        @Override
        public String toString() {
            return "Subtask[" + state + "]";
        }
    }

    private final ThreadFactory factory;
    private final Thread owner = Thread.currentThread();
    private final Object lock = new Object();
    private final java.util.ArrayList<SubtaskImpl<?>> running = new java.util.ArrayList<>();
    private volatile boolean shutdown;
    private boolean closed;
    private boolean forkedSinceJoin;

    public StructuredTaskScope() {
        this(null, runnable -> {
            Thread t = new Thread(runnable);
            t.setDaemon(true);
            return t;
        });
    }

    public StructuredTaskScope(String name, ThreadFactory factory) {
        this.factory = Objects.requireNonNull(factory);
    }

    public <U extends T> Subtask<U> fork(Callable<? extends U> task) {
        Objects.requireNonNull(task);
        if (Thread.currentThread() != owner) throw new WrongThreadException("Current thread not owner");
        SubtaskImpl<U> subtask = new SubtaskImpl<>(this, task);
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Task scope is closed");
            forkedSinceJoin = true;
            if (shutdown) return subtask; // not started, and stays UNAVAILABLE
            Thread thread = factory.newThread(subtask);
            if (thread == null) throw new java.util.concurrent.RejectedExecutionException("Rejected by thread factory");
            subtask.thread = thread;
            running.add(subtask);
            thread.start();
        }
        return subtask;
    }

    private void finished(SubtaskImpl<?> subtask) {
        synchronized (lock) {
            running.remove(subtask);
            lock.notifyAll();
        }
    }

    /** Called on the fork's own thread when it completes, unless the scope has been shut down. */
    protected void handleComplete(Subtask<? extends T> subtask) {}

    public StructuredTaskScope<T> join() throws InterruptedException {
        await(0L);
        return this;
    }

    public StructuredTaskScope<T> joinUntil(Instant deadline) throws InterruptedException, TimeoutException {
        long millis = Math.max(1L, Duration.between(Instant.now(), deadline).toMillis());
        if (!await(millis)) {
            shutdown();
            throw new TimeoutException();
        }
        return this;
    }

    /** Waits until no fork is running, or the scope is shut down. False when [millis] (if positive) ran out. */
    private boolean await(long millis) throws InterruptedException {
        if (Thread.currentThread() != owner) throw new WrongThreadException("Current thread not owner");
        long deadline = millis > 0 ? System.currentTimeMillis() + millis : 0L;
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Task scope is closed");
            while (!running.isEmpty() && !shutdown) {
                if (millis > 0) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) return false;
                    lock.wait(left);
                } else {
                    lock.wait();
                }
            }
            forkedSinceJoin = false;
        }
        return true;
    }

    public void shutdown() {
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Task scope is closed");
            if (shutdown) return;
            shutdown = true;
            for (SubtaskImpl<?> s : running) {
                if (s.thread != Thread.currentThread()) s.thread.interrupt();
            }
            lock.notifyAll();
        }
    }

    public final boolean isShutdown() {
        return shutdown;
    }

    @Override
    public void close() {
        if (Thread.currentThread() != owner) throw new WrongThreadException("Current thread not owner");
        boolean interrupted = false;
        synchronized (lock) {
            if (closed) return;
            if (!shutdown) {
                shutdown = true;
                for (SubtaskImpl<?> s : running) s.thread.interrupt();
            }
            while (!running.isEmpty()) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            closed = true;
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /** The owner may only read a subtask's outcome after joining the forks it started. */
    private void ensureJoinedIfOwner() {
        if (Thread.currentThread() == owner) {
            synchronized (lock) {
                if (forkedSinceJoin) throw new IllegalStateException("Owner did not join after forking subtasks");
            }
        }
    }

    protected final void ensureOwnerAndJoined() {
        if (Thread.currentThread() != owner) throw new WrongThreadException("Current thread not owner");
        ensureJoinedIfOwner();
    }

    /** Thrown when a scope is used from a thread that does not own it. */
    private static final class WrongThreadException extends RuntimeException {
        WrongThreadException(String message) {
            super(message);
        }
    }

    /** Shuts the scope down as soon as one fork succeeds, keeping that result. */
    public static final class ShutdownOnSuccess<T> extends StructuredTaskScope<T> {
        private final Object lock = new Object();
        private boolean hasResult;
        private T result;
        private Throwable firstFailure;

        public ShutdownOnSuccess() {
            super();
        }

        public ShutdownOnSuccess(String name, ThreadFactory factory) {
            super(name, factory);
        }

        @Override
        protected void handleComplete(Subtask<? extends T> subtask) {
            boolean won = false;
            synchronized (lock) {
                if (subtask.state() == Subtask.State.SUCCESS) {
                    if (!hasResult) {
                        hasResult = true;
                        result = ((SubtaskImpl<? extends T>) subtask).get0();
                        won = true;
                    }
                } else if (firstFailure == null) {
                    firstFailure = ((SubtaskImpl<? extends T>) subtask).failure0();
                }
            }
            if (won) super.shutdown();
        }

        @Override
        public ShutdownOnSuccess<T> join() throws InterruptedException {
            super.join();
            return this;
        }

        @Override
        public ShutdownOnSuccess<T> joinUntil(Instant deadline) throws InterruptedException, TimeoutException {
            super.joinUntil(deadline);
            return this;
        }

        public T result() throws ExecutionException {
            return result(ExecutionException::new);
        }

        public <X extends Throwable> T result(Function<Throwable, ? extends X> esf) throws X {
            Objects.requireNonNull(esf);
            ensureOwnerAndJoined();
            synchronized (lock) {
                if (hasResult) return result;
                if (firstFailure != null) throw esf.apply(firstFailure);
            }
            throw new IllegalStateException("No completed subtasks");
        }
    }

    /** Shuts the scope down as soon as one fork fails, keeping that exception. */
    public static final class ShutdownOnFailure extends StructuredTaskScope<Object> {
        private final Object lock = new Object();
        private Throwable firstFailure;

        public ShutdownOnFailure() {
            super();
        }

        public ShutdownOnFailure(String name, ThreadFactory factory) {
            super(name, factory);
        }

        @Override
        protected void handleComplete(Subtask<?> subtask) {
            boolean failed = false;
            synchronized (lock) {
                if (subtask.state() == Subtask.State.FAILED && firstFailure == null) {
                    firstFailure = ((SubtaskImpl<?>) subtask).failure0();
                    failed = true;
                }
            }
            if (failed) super.shutdown();
        }

        @Override
        public ShutdownOnFailure join() throws InterruptedException {
            super.join();
            return this;
        }

        @Override
        public ShutdownOnFailure joinUntil(Instant deadline) throws InterruptedException, TimeoutException {
            super.joinUntil(deadline);
            return this;
        }

        public Optional<Throwable> exception() {
            ensureOwnerAndJoined();
            synchronized (lock) {
                return Optional.ofNullable(firstFailure);
            }
        }

        public void throwIfFailed() throws ExecutionException {
            throwIfFailed(ExecutionException::new);
        }

        public <X extends Throwable> void throwIfFailed(Function<Throwable, ? extends X> esf) throws X {
            Objects.requireNonNull(esf);
            ensureOwnerAndJoined();
            Throwable failure;
            synchronized (lock) {
                failure = firstFailure;
            }
            if (failure != null) throw esf.apply(failure);
        }
    }
}
