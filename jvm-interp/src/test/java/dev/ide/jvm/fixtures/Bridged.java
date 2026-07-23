package dev.ide.jvm.fixtures;

/** Calls that must cross the bridge into real platform classes: a static method on java.lang.Math, and an
 *  instance method on a java.lang.String passed in from the caller. Proves the interpreter-to-real boundary. */
public final class Bridged {
    private Bridged() {}

    public static int maxOf(int a, int b) {
        return Math.max(a, b) + Math.min(a, b); // INVOKESTATIC java/lang/Math
    }

    public static int stringLength(String s) {
        return s.length(); // INVOKEVIRTUAL on a real String receiver
    }

    public static int charAtSum(String s) {
        int sum = 0;
        for (int i = 0; i < s.length(); i++) sum += s.charAt(i);
        return sum;
    }

    /** String concatenation lowers to an invokedynamic bootstrapped by StringConcatFactory. */
    public static String concat(int x) {
        return "v" + x;
    }
}
