package dev.ide.jvm.fixtures;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Concurrency fixtures for the interpreter's threading support. Each method is DETERMINISTIC when
 * synchronization works correctly (a final count after every worker has joined, a fixed sum), so the real
 * invocation on the host JVM is a valid oracle for the interpreted run: if the interpreter's monitors,
 * wait/notify, class initialization, or thread spawning were broken, the interpreted result would diverge
 * (lost updates, a deadlock, a wrong sum) instead of matching. Threads are started as bridged real
 * {@code java.lang.Thread}s whose {@code run} re-enters the interpreter.
 */
public final class Threading {

    private Threading() {}

    /** N threads each increment a shared counter M times inside a {@code synchronized} block (MONITORENTER /
     *  MONITOREXIT) over a plain Object lock. The total is {@code n*m} iff mutual exclusion holds. */
    public static int syncBlockCounter(int n, int m) throws InterruptedException {
        int[] counter = {0};
        Object lock = new Object();
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < m; j++) {
                    synchronized (lock) {
                        counter[0]++;
                    }
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        return counter[0];
    }

    /** Same contention, but the critical section is a {@code synchronized} METHOD (ACC_SYNCHRONIZED) on a
     *  shared interpreted object — exercises the method-level monitor rather than the bytecode ops. */
    public static int syncMethodCounter(int n, int m) throws InterruptedException {
        Counter c = new Counter();
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < m; j++) c.inc();
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        return c.get();
    }

    static final class Counter {
        private int value;
        synchronized void inc() { value++; }
        synchronized int get() { return value; }
    }

    /** Start a worker that computes a result into a field, join it, and return the field — the join pattern
     *  that used to deadlock under the single-threaded interpreter (main held the global lock while joining a
     *  worker that needed it). */
    public static long joinResult(int to) throws InterruptedException {
        Summer s = new Summer(to);
        Thread t = new Thread(s);
        t.start();
        t.join();
        return s.sum;
    }

    static final class Summer implements Runnable {
        final int to;
        long sum;
        Summer(int to) { this.to = to; }
        public void run() {
            long acc = 0;
            for (int i = 1; i <= to; i++) acc += i;
            sum = acc;
        }
    }

    /** Producer/consumer over a one-slot buffer using {@code Object.wait}/{@code notifyAll} on an interpreted
     *  monitor. The producer offers 1..count then a poison value; the consumer sums until poisoned. The result
     *  is {@code count*(count+1)/2} iff wait releases and reacquires the monitor correctly. */
    public static long produceConsume(int count) throws InterruptedException {
        Box box = new Box();
        long[] total = {0};
        Thread producer = new Thread(() -> {
            for (int i = 1; i <= count; i++) box.put(i);
            box.put(-1);
        });
        Thread consumer = new Thread(() -> {
            while (true) {
                int v = box.take();
                if (v < 0) break;
                total[0] += v;
            }
        });
        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
        return total[0];
    }

    static final class Box {
        private int value;
        private boolean full;

        synchronized void put(int v) {
            while (full) await();
            value = v;
            full = true;
            notifyAll();
        }

        synchronized int take() {
            while (!full) await();
            int v = value;
            full = false;
            notifyAll();
            return v;
        }

        private void await() {
            try {
                wait();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** N threads incrementing a bridged {@code AtomicInteger} M times — verifies the real java.util.concurrent
     *  primitive works unchanged through the bridge under genuine parallelism (no VM monitor involved). */
    public static int atomicCounter(int n, int m) throws InterruptedException {
        AtomicInteger counter = new AtomicInteger();
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < m; j++) counter.incrementAndGet();
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        return counter.get();
    }

    /** Set then read a volatile instance field, a plain instance field, and a volatile static field, so the
     *  interpreter's AtomicReference-backed volatile slots are exercised for read AND write (value semantics
     *  round-trip; the real invocation gives the same total). */
    public static long volatileRoundTrip(long v) {
        Holder h = new Holder();
        h.volatileVal = v;
        h.plainVal = v + 1;
        Holder.staticVolatileVal = v + 2;
        return h.volatileVal + h.plainVal + Holder.staticVolatileVal;
    }

    static final class Holder {
        volatile long volatileVal;
        long plainVal;
        static volatile long staticVolatileVal;
    }

    /** A worker spins on a volatile flag while the main thread flips it. Terminates only if the worker OBSERVES
     *  the write — a hang (caught by the test timeout) would mean visibility is broken. Returns the iteration
     *  count (positive), which is timing-dependent, so the test only checks it ran and stopped. */
    public static long stopFlagLoop() throws InterruptedException {
        Flag flag = new Flag();
        long[] iterations = {0};
        Thread worker = new Thread(() -> {
            while (!flag.stop) iterations[0]++;
        });
        worker.start();
        Thread.sleep(50);
        flag.stop = true;
        worker.join();
        return iterations[0];
    }

    static final class Flag {
        volatile boolean stop;
    }

    /** Recurse on a spawned thread until its host stack is exhausted, returning the depth reached (recorded in
     *  a shared array). The StackOverflowError is a host error the interpreter does not route to an interpreted
     *  catch, so it ends the worker; the recorded depth reflects how much stack that thread had — larger when
     *  the VM gave the thread a bigger stack. */
    public static int maxRecursionDepthOnThread() throws InterruptedException {
        int[] depth = {0};
        Thread t = new Thread(() -> deepen(depth));
        t.start();
        t.join();
        return depth[0];
    }

    private static void deepen(int[] depth) {
        depth[0]++;
        deepen(depth);
    }

    /** Spins until the current thread is interrupted, then returns how far it got. Used to check that a worker
     *  thread running an interpreted loop observes interruption of its host thread (through the bridged
     *  {@code Thread.isInterrupted}). */
    public static long spinUntilInterrupted() {
        long i = 0;
        while (!Thread.currentThread().isInterrupted()) i++;
        return i;
    }

    /** A pure-compute infinite loop with no bridge calls, so it only stops when the VM's periodic cancel check
     *  fires ({@link dev.ide.jvm.Vm#requestCancel}). */
    public static long spinForever() {
        long i = 0;
        while (true) i++;
    }
}
