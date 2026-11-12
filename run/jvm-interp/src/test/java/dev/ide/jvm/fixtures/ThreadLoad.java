package dev.ide.jvm.fixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Holds many virtual threads alive at once, to see how many a runtime that emulates them can carry. */
public final class ThreadLoad {
    private ThreadLoad() {}

    /** Starts {@code count} virtual threads that all wait, then lets them go. Reports how far it got. */
    public static String hold(int count) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(count);
        List<Thread> threads = new ArrayList<>();
        String stopped = "none";
        try {
            for (int i = 0; i < count; i++) {
                threads.add(Thread.ofVirtual().start(() -> {
                    started.countDown();
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }));
            }
        } catch (RuntimeException e) {
            stopped = e.getClass().getSimpleName();
        }
        int alive = threads.size();
        release.countDown();
        for (Thread t : threads) t.join();
        return alive + " " + stopped;
    }
}
