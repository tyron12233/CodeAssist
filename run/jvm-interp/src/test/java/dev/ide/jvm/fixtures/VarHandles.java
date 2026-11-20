package dev.ide.jvm.fixtures;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

/**
 * Variable handles as concurrent libraries use them: taken once in the class initializer over the class's own
 * fields (volatile and plain, primitive and reference, instance and static) and over array elements, then used
 * for compare-and-set loops.
 */
public class VarHandles {
    private volatile int state;
    private long total;
    private Object ref = "a";
    private boolean flag;
    private static volatile int counter;
    private static String label = "x";

    private static final VarHandle STATE, TOTAL, REF, FLAG, COUNTER, LABEL;
    private static final VarHandle INTS = MethodHandles.arrayElementVarHandle(int[].class);
    private static final VarHandle STRINGS = MethodHandles.arrayElementVarHandle(String[].class);

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            STATE = lookup.findVarHandle(VarHandles.class, "state", int.class);
            TOTAL = lookup.findVarHandle(VarHandles.class, "total", long.class);
            REF = lookup.findVarHandle(VarHandles.class, "ref", Object.class);
            FLAG = lookup.findVarHandle(VarHandles.class, "flag", boolean.class);
            COUNTER = lookup.findStaticVarHandle(VarHandles.class, "counter", int.class);
            LABEL = lookup.unreflectVarHandle(VarHandles.class.getDeclaredField("label"));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static String fields() {
        VarHandles o = new VarHandles();
        boolean first = STATE.compareAndSet(o, 0, 5);
        boolean second = STATE.compareAndSet(o, 0, 9);
        int before = (int) STATE.getAndAdd(o, 3);
        int witness = (int) STATE.compareAndExchange(o, 8, 300);
        boolean large = STATE.compareAndSet(o, 300, 301);
        int ored = (int) STATE.getAndBitwiseOr(o, 2);
        TOTAL.set(o, 10L);
        long totalBefore = (long) TOTAL.getAndAdd(o, 5L);
        Object old = REF.getAndSet(o, "b");
        boolean swapped = REF.compareAndSet(o, "b", null);
        FLAG.setVolatile(o, true);
        return first + "," + second + "," + before + "," + witness + "," + large + "," + ored + "," + o.state
            + "," + totalBefore + "," + o.total + "," + (long) TOTAL.getVolatile(o)
            + "," + old + "," + swapped + "," + o.ref + "," + o.flag + "," + (boolean) FLAG.get(o);
    }

    public static String statics() {
        COUNTER.set(4);
        int before = (int) COUNTER.getAndAdd(1);
        boolean swapped = COUNTER.compareAndSet(5, 6);
        String old = (String) LABEL.getAndSet("y");
        return before + "," + swapped + "," + counter + "," + (int) COUNTER.getAcquire() + "," + old + "," + label;
    }

    public static String arrays() {
        int[] xs = {1, 2, 3};
        boolean swapped = INTS.compareAndSet(xs, 1, 2, 20);
        int before = (int) INTS.getAndAdd(xs, 2, 5);
        String[] ss = {"x", null};
        boolean filled = STRINGS.compareAndSet(ss, 1, null, "y");
        STRINGS.setRelease(ss, 0, "z");
        String outOfBounds;
        try {
            INTS.get(xs, 3);
            outOfBounds = "read";
        } catch (ArrayIndexOutOfBoundsException e) {
            outOfBounds = "AIOOBE";
        }
        return swapped + "," + before + "," + filled + "," + Arrays.toString(xs) + Arrays.toString(ss)
            + "," + (int) INTS.get(xs, 0) + "," + outOfBounds;
    }

    /** Four threads each adding 2000 through a compare-and-set loop: nothing is lost. */
    public static String contended() throws InterruptedException {
        VarHandles o = new VarHandles();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 2000; i++) {
                    int seen;
                    do {
                        seen = (int) STATE.getVolatile(o);
                    } while (!STATE.compareAndSet(o, seen, seen + 1));
                    TOTAL.getAndAdd(o, 1L);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) thread.join();
        return o.state + "," + o.total;
    }
}
