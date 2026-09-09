package dev.ide.interp.impl.fixtures;

/** A class with state, a static, and a field, for driving a bytecode session the way a plugin would. */
public class Counter {

    public static int created = 0;

    private int value;

    public Counter(int start) {
        this.value = start;
        created++;
    }

    public int add(int n) {
        value += n;
        return value;
    }

    public int value() {
        return value;
    }

    public static String describe(int n) {
        return "n=" + n;
    }
}
