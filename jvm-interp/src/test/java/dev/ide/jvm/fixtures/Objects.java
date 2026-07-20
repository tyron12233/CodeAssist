package dev.ide.jvm.fixtures;

/** Object model: allocation, instance fields, constructors, inheritance, virtual dispatch, arrays, and
 *  try/catch. Everything here is interpretable (no platform types beyond Object.<init>), so it stays inside
 *  the VM's own object universe. */
public final class Objects {
    private Objects() {}

    static class Counter {
        private int value;
        Counter(int start) { this.value = start; }
        int inc() { return ++value; }
        int add(int n) { value += n; return value; }
        int get() { return value; }
    }

    static class Shape {
        int sides() { return 0; }
        int describe() { return sides() * 100; } // virtual call resolves to the runtime subclass
    }

    static class Triangle extends Shape {
        @Override int sides() { return 3; }
    }

    static class Square extends Shape {
        @Override int sides() { return 4; }
        @Override int describe() { return super.describe() + 1; } // super call
    }

    public static int counter(int start, int times) {
        Counter c = new Counter(start);
        for (int i = 0; i < times; i++) c.inc();
        return c.add(10);
    }

    public static int polymorphism(int which) {
        Shape s = which == 0 ? new Triangle() : new Square();
        return s.describe();
    }

    public static int arraySum(int n) {
        int[] xs = new int[n];
        for (int i = 0; i < n; i++) xs[i] = i * i;
        int sum = 0;
        for (int x : xs) sum += x;
        return sum;
    }

    public static int matrixTrace(int n) {
        int[][] m = new int[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                m[i][j] = i * n + j;
        int trace = 0;
        for (int i = 0; i < n; i++) trace += m[i][i];
        return trace;
    }

    public static int catchDivByZero(int a, int b) {
        try {
            return a / b;
        } catch (ArithmeticException e) {
            return -1;
        }
    }

    public static int catchArrayBounds(int n, int idx) {
        int[] xs = new int[n];
        try {
            return xs[idx];
        } catch (ArrayIndexOutOfBoundsException e) {
            return -999;
        } finally {
            xs[0] = 1; // finally runs regardless
        }
    }

    public static boolean instanceCheck(int which) {
        Shape s = which == 0 ? new Triangle() : new Square();
        return s instanceof Triangle;
    }
}
