package dev.ide.jvm.fixtures;

import java.util.ArrayList;

/** Construction of platform classes with the new/dup/init sequence: a builder, a collection, a thrown
 *  exception object, and a nested construction where one constructed object is an argument to another. */
public final class Construct {
    private Construct() {}

    public static String build(String who, int n) {
        StringBuilder sb = new StringBuilder();
        sb.append("hi ");
        sb.append(who);
        sb.append(' ');
        sb.append(n);
        sb.append('!');
        return sb.toString();
    }

    public static int listSum(int n) {
        ArrayList<Integer> xs = new ArrayList<>();
        for (int i = 0; i < n; i++) xs.add(i * i);
        int sum = 0;
        for (int i = 0; i < xs.size(); i++) sum += xs.get(i);
        return sum + xs.size();
    }

    public static int safeDiv(int a, int b) {
        try {
            return checkedDiv(a, b);
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private static int checkedDiv(int a, int b) {
        if (b == 0) throw new IllegalArgumentException("divisor is zero");
        return a / b;
    }

    public static String nestedCause() {
        RuntimeException e = new RuntimeException("outer", new IllegalStateException("inner"));
        return e.getMessage() + "/" + e.getCause().getMessage();
    }
}
