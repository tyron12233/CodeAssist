package dev.ide.jvm.fixtures;

import java.util.Arrays;

/** An exception thrown by a platform call is caught by an interpreted handler, and an array mutated in place by
 *  a platform call is visible to interpreted code afterward. */
public final class Recover {
    private Recover() {}

    public static int parseOrDefault(String s, int fallback) {
        try {
            return Integer.parseInt(s); // throws NumberFormatException on bad input
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static int sortFirst(int a, int b, int c) {
        int[] xs = { a, b, c };
        Arrays.sort(xs); // mutates the array in place
        return xs[0];
    }

    public static int fillSum(int n, int value) {
        int[] xs = new int[n];
        Arrays.fill(xs, value); // mutates in place
        int sum = 0;
        for (int x : xs) sum += x;
        return sum;
    }
}
