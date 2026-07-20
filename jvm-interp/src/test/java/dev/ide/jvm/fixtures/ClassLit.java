package dev.ide.jvm.fixtures;

/** Class literals, which the compiler emits as ldc of a type constant (an array literal too), and a class
 *  identity comparison. */
public final class ClassLit {
    private ClassLit() {}

    public static String stringClassName() { return String.class.getName(); }

    public static String intArrayClassName() { return int[].class.getName(); }

    public static boolean isString(Object o) { return o.getClass() == String.class; }
}
