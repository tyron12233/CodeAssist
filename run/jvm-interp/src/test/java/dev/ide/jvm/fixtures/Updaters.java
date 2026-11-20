package dev.ide.jvm.fixtures;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Atomic field updaters over the class's own volatile fields, the pre-`VarHandle` way a concurrent library
 * (and everything compiled with atomicfu) updates a field without a lock.
 */
public class Updaters {
    volatile int count;
    volatile long big;
    volatile String name = "a";

    static final AtomicIntegerFieldUpdater<Updaters> COUNT = AtomicIntegerFieldUpdater.newUpdater(Updaters.class, "count");
    static final AtomicLongFieldUpdater<Updaters> BIG = AtomicLongFieldUpdater.newUpdater(Updaters.class, "big");
    static final AtomicReferenceFieldUpdater<Updaters, String> NAME =
        AtomicReferenceFieldUpdater.newUpdater(Updaters.class, String.class, "name");

    public static String ints() {
        Updaters o = new Updaters();
        boolean first = COUNT.compareAndSet(o, 0, 5);
        boolean second = COUNT.compareAndSet(o, 0, 9);
        int incremented = COUNT.incrementAndGet(o);
        int beforeAdd = COUNT.getAndAdd(o, 10);
        int added = COUNT.addAndGet(o, -1);
        int beforeUpdate = COUNT.getAndUpdate(o, x -> x * 2);
        int accumulated = COUNT.accumulateAndGet(o, 5, (a, b) -> a + b);
        int maxed = COUNT.updateAndGet(o, x -> Math.max(x, 1));
        COUNT.lazySet(o, 400);
        boolean large = COUNT.weakCompareAndSet(o, 400, 401);
        int decremented = COUNT.decrementAndGet(o);
        int beforeIncrement = COUNT.getAndIncrement(o);
        return first + "," + second + "," + incremented + "," + beforeAdd + "," + added + "," + beforeUpdate
            + "," + accumulated + "," + maxed + "," + large + "," + decremented + "," + beforeIncrement
            + "," + o.count + "," + COUNT.get(o);
    }

    public static String longs() {
        Updaters o = new Updaters();
        BIG.set(o, 1L << 40);
        long before = BIG.getAndIncrement(o);
        boolean swapped = BIG.compareAndSet(o, (1L << 40) + 1, 7L);
        long updated = BIG.updateAndGet(o, x -> x * 3);
        long beforeAccumulate = BIG.getAndAccumulate(o, 4L, Math::max);
        return before + "," + swapped + "," + updated + "," + beforeAccumulate + "," + BIG.addAndGet(o, 100L) + "," + o.big;
    }

    public static String references() {
        Updaters o = new Updaters();
        boolean swapped = NAME.compareAndSet(o, "a", "b");
        boolean stale = NAME.compareAndSet(o, "a", "c");
        String old = NAME.getAndSet(o, "d");
        String updated = NAME.updateAndGet(o, s -> s + "!");
        String beforeAccumulate = NAME.getAndAccumulate(o, "?", (s, x) -> s + x);
        return swapped + "," + stale + "," + old + "," + updated + "," + beforeAccumulate + "," + o.name + "," + NAME.get(o);
    }

    public static String missing() {
        try {
            AtomicIntegerFieldUpdater.newUpdater(Updaters.class, "nope");
            return "made";
        } catch (RuntimeException e) {
            return e.getCause() == null ? e.getClass().getSimpleName() : e.getCause().getClass().getSimpleName();
        }
    }

    /** Four threads each incrementing 2000 times: nothing is lost. */
    public static String contended() throws InterruptedException {
        Updaters o = new Updaters();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 2000; i++) {
                    COUNT.incrementAndGet(o);
                    BIG.getAndUpdate(o, x -> x + 2);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) thread.join();
        return o.count + "," + o.big;
    }
}
