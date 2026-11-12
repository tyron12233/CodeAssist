package dev.ide.jvm.fixtures;

import dev.ide.jvm.standin.java.lang.ScopedValue;
import dev.ide.jvm.standin.java.util.concurrent.StructuredTaskScope;

import java.util.concurrent.ExecutionException;

/** Programs over the {@code ScopedValue} and {@code StructuredTaskScope} stand-ins, written against the names
 *  they are compiled under; the VM runs the same code with those names moved into {@code java.*}. */
public final class Structured {
    private Structured() {}

    private static final ScopedValue<String> USER = ScopedValue.newInstance();
    private static final ScopedValue<Integer> DEPTH = ScopedValue.newInstance();

    public static String scopedValueBasics() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(USER.isBound()).append(' ').append(USER.orElse("nobody")).append(' ');
        ScopedValue.runWhere(USER, "ada", () -> {
            sb.append(USER.get()).append(' ');
            ScopedValue.where(USER, "bob").where(DEPTH, 2).run(() -> sb.append(USER.get()).append(DEPTH.get()).append(' '));
            sb.append(USER.get()).append(DEPTH.isBound()).append(' ');
        });
        String called = ScopedValue.callWhere(DEPTH, 7, () -> "d" + DEPTH.get());
        String supplied = ScopedValue.getWhere(USER, "eve", () -> USER.get().toUpperCase());
        try {
            ScopedValue.runWhere(USER, "x", () -> { throw new IllegalStateException("boom"); });
        } catch (IllegalStateException e) {
            sb.append(e.getMessage()).append(USER.isBound()).append(' ');
        }
        String unbound;
        try { USER.get(); unbound = "no"; } catch (java.util.NoSuchElementException e) { unbound = "nse"; }
        String thrown;
        try { USER.orElseThrow(() -> new IllegalArgumentException("none")); thrown = "no"; } catch (IllegalArgumentException e) { thrown = e.getMessage(); }
        return sb + called + " " + supplied + " " + unbound + " " + thrown + " " + ScopedValue.where(DEPTH, 3).get(DEPTH);
    }

    public static String allForksSucceed() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var a = scope.fork(() -> 20);
            var b = scope.fork(() -> "x".repeat(3));
            var c = scope.fork(() -> { Thread.sleep(20); return 2; });
            scope.join().throwIfFailed();
            return (a.get() + c.get()) + b.get() + " " + a.state() + " " + scope.exception().isPresent() + " " + scope.isShutdown();
        }
    }

    public static String aFailedForkShutsTheScopeDown() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var slow = scope.fork(() -> { Thread.sleep(30_000); return "late"; });
            var bad = scope.fork(() -> { throw new IllegalStateException("broken"); });
            scope.join();
            String rethrown;
            try { scope.throwIfFailed(); rethrown = "no"; } catch (ExecutionException e) { rethrown = e.getCause().getMessage(); }
            String custom;
            try { scope.throwIfFailed(t -> new IllegalArgumentException("wrapped " + t.getMessage())); custom = "no"; }
            catch (IllegalArgumentException e) { custom = e.getMessage(); }
            return rethrown + " " + custom + " " + bad.state() + " " + bad.exception().getMessage() + " " + slow.state() + " " + scope.isShutdown();
        }
    }

    public static String theFirstSuccessWins() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnSuccess<String>()) {
            scope.fork(() -> { Thread.sleep(30_000); return "slow"; });
            scope.fork(() -> "fast");
            return scope.join().result() + " " + scope.isShutdown();
        }
    }

    public static String everyForkFailing() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnSuccess<String>()) {
            scope.fork(() -> { throw new IllegalStateException("only"); });
            scope.join();
            try { return scope.result(); } catch (ExecutionException e) { return "failed: " + e.getCause().getMessage(); }
        }
    }

    public static String aForkSeesTheOwnersBinding() throws Exception {
        return ScopedValue.callWhere(USER, "ada", () -> {
            try (var scope = new StructuredTaskScope<String, Void>()) {
                var who = scope.fork(() -> USER.get() + Thread.currentThread().isDaemon());
                scope.join();
                return who.get();
            }
        });
    }

    public static String theOwnerHasToJoinBeforeReading() throws Exception {
        try (var scope = new StructuredTaskScope<Integer, Void>()) {
            var one = scope.fork(() -> 1);
            String early;
            try { one.get(); early = "no"; } catch (IllegalStateException e) { early = "must join"; }
            scope.join();
            scope.close();
            String closed;
            try { scope.fork(() -> 2); closed = "no"; } catch (IllegalStateException e) { closed = "closed"; }
            return early + " " + one.get() + " " + closed;
        }
    }

    // ---- the Java 25 shapes ---------------------------------------------------------------------------

    public static String openAwaitsEveryFork() throws Exception {
        try (var scope = StructuredTaskScope.open()) {
            var a = scope.fork(() -> 20);
            var b = scope.fork(() -> "x".repeat(2));
            int[] ran = new int[1];
            var c = scope.fork(() -> { ran[0] = 7; });
            Object joined = scope.join();
            return a.get() + b.get() + " " + ran[0] + " " + c.state() + " " + joined + " " + scope.isCancelled();
        }
    }

    public static String openThrowsTheFirstFailure() throws Exception {
        try (var scope = StructuredTaskScope.open()) {
            scope.fork(() -> { Thread.sleep(30_000); return "late"; });
            scope.fork(() -> { throw new IllegalStateException("broken"); });
            try {
                scope.join();
                return "no";
            } catch (StructuredTaskScope.FailedException e) {
                return e.getCause().getClass().getSimpleName() + ":" + e.getCause().getMessage() + " " + scope.isCancelled();
            }
        }
    }

    public static String anySuccessfulResult() throws Exception {
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<String>anySuccessfulResultOrThrow())) {
            scope.fork(() -> { Thread.sleep(30_000); return "slow"; });
            scope.fork(() -> "fast");
            return scope.join();
        }
    }

    public static String allSuccessfulResults() throws Exception {
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<Integer>allSuccessfulOrThrow())) {
            for (int i = 1; i <= 4; i++) { int n = i; scope.fork(() -> n * n); }
            return "" + scope.join().mapToInt(StructuredTaskScope.Subtask::get).sum();
        }
    }

    public static String theTimeoutCancelsTheScope() throws Exception {
        try (var scope = StructuredTaskScope.open(
            StructuredTaskScope.Joiner.<String>awaitAll(), cf -> cf.withName("t").withTimeout(java.time.Duration.ofMillis(60)))) {
            var slow = scope.fork(() -> { Thread.sleep(30_000); return "slow"; });
            try {
                scope.join();
                return "no";
            } catch (StructuredTaskScope.TimeoutException e) {
                return "timeout " + slow.state() + " " + scope.isCancelled();
            }
        }
    }

    public static String allUntilStopsEarly() throws Exception {
        var done = new java.util.concurrent.atomic.AtomicInteger();
        try (var scope = StructuredTaskScope.open(
            StructuredTaskScope.Joiner.<String>allUntil(s -> done.incrementAndGet() >= 2))) {
            scope.fork(() -> "a");
            scope.fork(() -> "b");
            scope.fork(() -> { Thread.sleep(30_000); return "c"; });
            long finished = scope.join().filter(s -> s.state() == StructuredTaskScope.Subtask.State.SUCCESS).count();
            return finished + " " + scope.isCancelled();
        }
    }

    public static String awaitAllIgnoresFailures() throws Exception {
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<String>awaitAll())) {
            var ok = scope.fork(() -> "ok");
            var bad = scope.fork(() -> { throw new IllegalStateException("x"); });
            scope.join();
            return ok.get() + " " + bad.state() + " " + bad.exception().getMessage();
        }
    }

    public static String carrierCallWithACheckedException() {
        try {
            return ScopedValue.where(USER, "ada").call(() -> {
                if (USER.get().length() == 3) throw new java.io.IOException("io");
                return "no";
            });
        } catch (java.io.IOException e) {
            return e.getMessage() + USER.isBound();
        }
    }
}
