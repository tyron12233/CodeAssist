package dev.ide.jvm.fixtures;

/** Pure integer/long/float/double computation: exercises arithmetic, conversions, comparisons, and loops. No
 *  object allocation and no platform calls, so the interpreter can run it with nothing crossing the bridge. */
public final class Arithmetic {
    private Arithmetic() {}

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

    public static int fibRecursive(int n) {
        if (n < 2) return n;
        return fibRecursive(n - 1) + fibRecursive(n - 2);
    }

    public static long factorial(int n) {
        long r = 1;
        for (int i = 2; i <= n; i++) r *= i;
        return r;
    }

    public static int gcd(int a, int b) {
        while (b != 0) {
            int t = b;
            b = a % b;
            a = t;
        }
        return a;
    }

    public static int bitwise(int a, int b) {
        return ((a & b) | (a ^ b)) << 1 >> 1 >>> 0;
    }

    public static double mixedMath(int i, long l, float f, double d) {
        double sum = i + l + f + d;
        return sum / 2.0 - (i * 3.0) + Math.abs(-f);
    }

    public static int intDiv(int a, int b) {
        return a / b + a % b; // throws ArithmeticException when b == 0
    }

    public static int branchy(int x) {
        int r;
        if (x < 0) r = -1;
        else if (x == 0) r = 0;
        else r = 1;
        return r * 10 + (x > 100 ? 5 : 2);
    }

    public static int switchColor(int code) {
        switch (code) {
            case 1: return 100;
            case 2: return 200;
            case 5: return 500;
            case 10: return 1000;
            default: return -1;
        }
    }
}
