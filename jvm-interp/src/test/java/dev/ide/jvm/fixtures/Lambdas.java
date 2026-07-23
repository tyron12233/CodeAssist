package dev.ide.jvm.fixtures;

import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;

/** Lambdas, method references, and string concatenation, all of which the compiler lowers to invokedynamic.
 *  These exercise LambdaMetafactory (consumed by interpreted code and passed to a platform API) and
 *  StringConcatFactory, plus one unsupported bootstrap (a record's generated members). */
public final class Lambdas {
    private Lambdas() {}

    static int applyTwice(IntUnaryOperator op, int x) {
        return op.applyAsInt(op.applyAsInt(x));
    }

    /** A capturing lambda consumed entirely within interpreted code. */
    public static int incTwice(int x, int by) {
        return applyTwice(v -> v + by, x);
    }

    /** A non-capturing lambda and a capturing lambda used together. */
    public static int combine(int a, int b) {
        IntBinaryOperator add = (x, y) -> x + y;
        IntUnaryOperator scale = v -> v * a;
        return scale.applyAsInt(add.applyAsInt(a, b));
    }

    /** A method reference to a platform method, invoked from interpreted code. */
    public static int refLength(String s) {
        ToIntFunction<String> length = String::length;
        return length.applyAsInt(s);
    }

    /** A lambda passed to a platform API, where it is invoked by real code through a proxy. */
    public static int mapSum(int n, int add) {
        return IntStream.range(0, n).map(v -> v + add).sum();
    }

    /** String concatenation with a constant and dynamic arguments (StringConcatFactory). */
    public static String describe(String name, int count) {
        return name + " x" + count + "!";
    }

    record Point(int x, int y) {}

    /** A record's toString/equals/hashCode are generated through the ObjectMethods bootstrap. */
    public static String recordToString() {
        return new Point(1, 2).toString();
    }

    public static boolean recordEquals() {
        return new Point(1, 2).equals(new Point(1, 2)) && !new Point(1, 2).equals(new Point(1, 3));
    }

    public static boolean recordHashConsistent() {
        return new Point(1, 2).hashCode() == new Point(1, 2).hashCode()
            && new Point(1, 2).hashCode() != new Point(9, 9).hashCode();
    }
}
