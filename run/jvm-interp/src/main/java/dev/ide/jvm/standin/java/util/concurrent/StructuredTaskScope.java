package dev.ide.jvm.standin.java.util.concurrent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * {@code java.util.concurrent.StructuredTaskScope} for a runtime that has none. The VM interprets this class
 * under that name (see {@code PlatformStandIns}).
 *
 * <p>The API changed between its previews, and both shapes are here so a program written against either
 * compiles: Java 25's ({@link #open()}, {@link Joiner}, {@code join()} returning the joiner's result) and
 * Java 21's ({@link ShutdownOnFailure}, {@link ShutdownOnSuccess}, {@code join()} returning the scope). One
 * thing could not be kept: Java 21's class had a single type parameter, so a Java 21 program that names the
 * base type with one argument ({@code new StructuredTaskScope<String>()}) has to use the diamond instead.
 *
 * <p>Each fork runs on a daemon thread of its own. The scope's owner forks, joins and closes; {@code join}
 * waits for every fork or for the scope to be cancelled, which interrupts the forks still running.
 *
 * @param <T> the result type of the forks
 * @param <R> what {@code join} returns
 */
public class StructuredTaskScope<T, R> implements AutoCloseable {

    /** A forked subtask. */
    public interface Subtask<T> extends Supplier<T> {
        enum State { UNAVAILABLE, SUCCESS, FAILED }

        /** Java 21 only. */
        Callable<? extends T> task();

        State state();

        T get();

        Throwable exception();
    }

    /** Decides when a Java 25 scope is done and what {@code join} returns. */
    public interface Joiner<T, R> {
        default boolean onFork(Subtask<? extends T> subtask) {
            return false;
        }

        default boolean onComplete(Subtask<? extends T> subtask) {
            return false;
        }

        R result() throws Throwable;

        /** All forks' subtasks once every one succeeded; the first failure cancels the scope and is thrown. */
        static <T> Joiner<T, Stream<Subtask<T>>> allSuccessfulOrThrow() {
            return new Collecting<T, Stream<Subtask<T>>>(true, null) {
                @Override
                public Stream<Subtask<T>> result() throws Throwable {
                    throwIfFailed();
                    return subtasks();
                }
            };
        }

        /** The first successful result, cancelling the rest; throws when every fork failed. */
        static <T> Joiner<T, T> anySuccessfulResultOrThrow() {
            return new Joiner<T, T>() {
                private boolean hasResult;
                private T result;
                private Throwable firstFailure;

                @Override
                public synchronized boolean onComplete(Subtask<? extends T> subtask) {
                    if (subtask.state() == Subtask.State.SUCCESS) {
                        if (!hasResult) {
                            hasResult = true;
                            result = ((SubtaskImpl<? extends T>) subtask).get0();
                        }
                        return true;
                    }
                    if (firstFailure == null) firstFailure = ((SubtaskImpl<? extends T>) subtask).failure0();
                    return false;
                }

                @Override
                public synchronized T result() throws Throwable {
                    if (hasResult) return result;
                    if (firstFailure != null) throw firstFailure;
                    throw new NoSuchElementException("No subtasks completed");
                }
            };
        }

        /** Nothing, once every fork succeeded; the first failure cancels the scope and is thrown. */
        static <T> Joiner<T, Void> awaitAllSuccessfulOrThrow() {
            return new Collecting<T, Void>(true, null) {
                @Override
                public Void result() throws Throwable {
                    throwIfFailed();
                    return null;
                }
            };
        }

        /** Nothing, once every fork finished, whether it succeeded or failed. */
        static <T> Joiner<T, Void> awaitAll() {
            return new Collecting<T, Void>(false, null) {
                @Override
                public Void result() {
                    return null;
                }
            };
        }

        /** All forks' subtasks, once every fork finished or {@code isDone} accepted a completed one. */
        static <T> Joiner<T, Stream<Subtask<T>>> allUntil(Predicate<Subtask<? extends T>> isDone) {
            Objects.requireNonNull(isDone);
            return new Collecting<T, Stream<Subtask<T>>>(false, isDone) {
                @Override
                public Stream<Subtask<T>> result() {
                    return subtasks();
                }
            };
        }
    }

    /** The joiners that keep every fork's subtask, in fork order. */
    private abstract static class Collecting<T, R> implements Joiner<T, R> {
        private final boolean cancelOnFailure;
        private final Predicate<Subtask<? extends T>> isDone;
        private final List<Subtask<T>> subtasks = new ArrayList<>();
        private Throwable firstFailure;

        Collecting(boolean cancelOnFailure, Predicate<Subtask<? extends T>> isDone) {
            this.cancelOnFailure = cancelOnFailure;
            this.isDone = isDone;
        }

        @Override
        @SuppressWarnings("unchecked")
        public synchronized boolean onFork(Subtask<? extends T> subtask) {
            subtasks.add((Subtask<T>) subtask);
            return false;
        }

        @Override
        public boolean onComplete(Subtask<? extends T> subtask) {
            synchronized (this) {
                if (subtask.state() == Subtask.State.FAILED && firstFailure == null) {
                    firstFailure = ((SubtaskImpl<? extends T>) subtask).failure0();
                    if (cancelOnFailure) return true;
                }
            }
            return isDone != null && isDone.test(subtask);
        }

        synchronized void throwIfFailed() throws Throwable {
            if (firstFailure != null) throw firstFailure;
        }

        synchronized Stream<Subtask<T>> subtasks() {
            return new ArrayList<>(subtasks).stream();
        }
    }

    /** How a Java 25 scope is set up: passed through the function given to {@link #open(Joiner, Function)}. */
    public interface Configuration {
        Configuration withThreadFactory(ThreadFactory threadFactory);

        Configuration withName(String name);

        Configuration withTimeout(Duration timeout);
    }

    private static final class Config implements Configuration {
        final ThreadFactory factory;
        final Duration timeout;

        Config(ThreadFactory factory, Duration timeout) {
            this.factory = factory;
            this.timeout = timeout;
        }

        @Override
        public Configuration withThreadFactory(ThreadFactory threadFactory) {
            return new Config(Objects.requireNonNull(threadFactory), timeout);
        }

        @Override
        public Configuration withName(String name) {
            Objects.requireNonNull(name);
            return this;
        }

        @Override
        public Configuration withTimeout(Duration timeout) {
            return new Config(factory, Objects.requireNonNull(timeout));
        }
    }

    /** Thrown by a Java 25 {@code join} when the joiner's outcome is a failure; the cause is that failure. */
    public static final class FailedException extends RuntimeException {
        FailedException(Throwable cause) {
            super(cause);
        }
    }

    /** Thrown by a Java 25 {@code join} when the scope's timeout expired first. */
    public static final class TimeoutException extends RuntimeException {
        TimeoutException() {}
    }

    private static final class SubtaskImpl<T> implements Subtask<T>, Runnable {
        private final StructuredTaskScope<? super T, ?> scope;
        private final Callable<? extends T> task;
        private volatile State state = State.UNAVAILABLE;
        private volatile Object outcome;
        Thread thread;

        SubtaskImpl(StructuredTaskScope<? super T, ?> scope, Callable<? extends T> task) {
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
            // A fork that finishes after the scope was cancelled stays UNAVAILABLE, as on the platform.
            if (!scope.cancelled) {
                if (failure == null) {
                    outcome = result;
                    state = State.SUCCESS;
                } else {
                    outcome = failure;
                    state = State.FAILED;
                }
                scope.completed(this);
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

        /** The result, read on the fork's own thread when it completes, where no join is owed. */
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

    private static final ThreadFactory DAEMON_THREADS = runnable -> {
        Thread t = new Thread(runnable);
        t.setDaemon(true);
        return t;
    };

    private final ThreadFactory factory;
    /** The Java 25 joiner, or null for a scope made with a constructor (Java 21). */
    private final Joiner<? super T, ? extends R> joiner;
    /** When a Java 25 scope's timeout runs out, in {@code System.currentTimeMillis} terms; 0 for none. */
    private final long deadline;
    private final Thread owner = Thread.currentThread();
    private final Object lock = new Object();
    private final ArrayList<SubtaskImpl<?>> running = new ArrayList<>();
    private volatile boolean cancelled;
    private boolean closed;
    private boolean forkedSinceJoin;

    // ---- Java 25 ------------------------------------------------------------------------------------

    public static <T> StructuredTaskScope<T, Void> open() {
        return open(Joiner.<T>awaitAllSuccessfulOrThrow());
    }

    public static <T, R> StructuredTaskScope<T, R> open(Joiner<? super T, ? extends R> joiner) {
        return open(joiner, Function.identity());
    }

    public static <T, R> StructuredTaskScope<T, R> open(
        Joiner<? super T, ? extends R> joiner, Function<Configuration, Configuration> configFunction
    ) {
        Objects.requireNonNull(joiner);
        Config config = (Config) Objects.requireNonNull(configFunction.apply(new Config(DAEMON_THREADS, null)));
        return new StructuredTaskScope<>(joiner, config);
    }

    private StructuredTaskScope(Joiner<? super T, ? extends R> joiner, Config config) {
        this.factory = config.factory;
        this.joiner = joiner;
        this.deadline = config.timeout == null ? 0L : System.currentTimeMillis() + Math.max(1L, config.timeout.toMillis());
    }

    public <U extends T> Subtask<U> fork(Runnable task) {
        Objects.requireNonNull(task);
        return fork(() -> {
            task.run();
            return null;
        });
    }

    /** Whether the scope has been cancelled (Java 25's name for what Java 21 called shut down). */
    public boolean isCancelled() {
        return cancelled;
    }

    // ---- Java 21 ------------------------------------------------------------------------------------

    public StructuredTaskScope() {
        this(null, DAEMON_THREADS);
    }

    public StructuredTaskScope(String name, ThreadFactory factory) {
        this.factory = Objects.requireNonNull(factory);
        this.joiner = null;
        this.deadline = 0L;
    }

    /** Called on the fork's own thread when it completes, unless the scope has been shut down. */
    protected void handleComplete(Subtask<? extends T> subtask) {}

    public StructuredTaskScope<T, R> joinUntil(Instant deadline) throws InterruptedException, java.util.concurrent.TimeoutException {
        long millis = Math.max(1L, Duration.between(Instant.now(), deadline).toMillis());
        if (!await(System.currentTimeMillis() + millis)) {
            shutdown();
            throw new java.util.concurrent.TimeoutException();
        }
        return this;
    }

    public void shutdown() {
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Task scope is closed");
        }
        cancel();
    }

    public final boolean isShutdown() {
        return cancelled;
    }

    protected final void ensureOwnerAndJoined() {
        if (Thread.currentThread() != owner) throw new WrongThreadException("Current thread not owner");
        ensureJoinedIfOwner();
    }

    // ---- both ---------------------------------------------------------------------------------------

    public <U extends T> Subtask<U> fork(Callable<? extends U> task) {
        Objects.requireNonNull(task);
        if (Thread.currentThread() != owner) throw new WrongThreadException("Current thread not owner");
        SubtaskImpl<U> subtask = new SubtaskImpl<>(this, task);
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Task scope is closed");
            forkedSinceJoin = true;
        }
        if (joiner != null && joiner.onFork(subtask)) cancel();
        synchronized (lock) {
            if (cancelled) return subtask; // not started, and stays UNAVAILABLE
            Thread thread = factory.newThread(subtask);
            if (thread == null) throw new java.util.concurrent.RejectedExecutionException("Rejected by thread factory");
            subtask.thread = thread;
            running.add(subtask);
            thread.start();
        }
        return subtask;
    }

    /**
     * Waits for the forks. A Java 25 scope returns its joiner's result, throwing {@link FailedException} when
     * that result is a failure and {@link TimeoutException} when the scope's timeout ran out. A Java 21 scope
     * returns nothing here; its two subclasses return themselves.
     */
    public R join() throws InterruptedException {
        if (!await(deadline)) {
            cancel();
            throw new TimeoutException();
        }
        if (joiner == null) return null;
        try {
            return joiner.result();
        } catch (Throwable failure) {
            throw new FailedException(failure);
        }
    }

    /** Waits until no fork is running or the scope is cancelled. False when {@code until} (if set) passed first. */
    private boolean await(long until) throws InterruptedException {
        if (Thread.currentThread() != owner) throw new WrongThreadException("Current thread not owner");
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Task scope is closed");
            while (!running.isEmpty() && !cancelled) {
                if (until > 0) {
                    long left = until - System.currentTimeMillis();
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

    private void completed(SubtaskImpl<? extends T> subtask) {
        if (joiner != null) {
            if (joiner.onComplete(subtask)) cancel();
        } else {
            handleComplete(subtask);
        }
    }

    private void finished(SubtaskImpl<?> subtask) {
        synchronized (lock) {
            running.remove(subtask);
            lock.notifyAll();
        }
    }

    private void cancel() {
        synchronized (lock) {
            if (cancelled) return;
            cancelled = true;
            for (SubtaskImpl<?> s : running) {
                if (s.thread != Thread.currentThread()) s.thread.interrupt();
            }
            lock.notifyAll();
        }
    }

    @Override
    public void close() {
        if (Thread.currentThread() != owner) throw new WrongThreadException("Current thread not owner");
        boolean interrupted = false;
        synchronized (lock) {
            if (closed) return;
            if (!cancelled) {
                cancelled = true;
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

    /** Thrown when a scope is used from a thread that does not own it. */
    private static final class WrongThreadException extends RuntimeException {
        WrongThreadException(String message) {
            super(message);
        }
    }

    /** Java 21: shuts the scope down as soon as one fork succeeds, keeping that result. */
    public static final class ShutdownOnSuccess<T> extends StructuredTaskScope<T, ShutdownOnSuccess<T>> {
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
        public ShutdownOnSuccess<T> joinUntil(Instant deadline) throws InterruptedException, java.util.concurrent.TimeoutException {
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

    /** Java 21: shuts the scope down as soon as one fork fails, keeping that exception. */
    public static final class ShutdownOnFailure extends StructuredTaskScope<Object, ShutdownOnFailure> {
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
        public ShutdownOnFailure joinUntil(Instant deadline) throws InterruptedException, java.util.concurrent.TimeoutException {
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
