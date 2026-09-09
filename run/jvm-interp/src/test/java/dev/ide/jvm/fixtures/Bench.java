package dev.ide.jvm.fixtures;

/** Compute-only fixtures for measuring interpreter throughput: a long counting loop (per-instruction cost)
 *  and a small method re-invoked many times (per-call cost, the recomposition pattern). */
public final class Bench {
    private Bench() {}

    public static long sumTo(int n) {
        long s = 0;
        for (int i = 0; i < n; i++) s += i;
        return s;
    }

    public static int fib(int n) {
        if (n < 2) return n;
        int a = 0, b = 1;
        for (int i = 2; i <= n; i++) {
            int c = a + b;
            a = b;
            b = c;
        }
        return b;
    }
}
