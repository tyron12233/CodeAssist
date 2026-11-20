package dev.ide.jvm.fixtures;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * `java.lang.invoke` as libraries use it: a logger named after `MethodHandles.lookup().lookupClass()`, and
 * handles found on the library's own classes (Jetty's `TypeUtil`, Spring). `lookup()` reads its caller off the
 * stack, so one forwarded through reflection sees the reflection machinery as the caller.
 */
public class Handles {
    private int count = 3;
    static String tag = "t";

    public Handles() {}

    public Handles(int count) {
        this.count = count;
    }

    private static String greet(String name, boolean loud) {
        return loud ? "HI " + name : "hi " + name;
    }

    public String shout(String s) {
        return s.toUpperCase() + count;
    }

    static int fail(int code) {
        throw new IllegalStateException("code " + code);
    }

    static class Louder extends Handles {
        @Override
        public String shout(String s) {
            return "!" + s;
        }

        static String inner() {
            return MethodHandles.lookup().lookupClass().getSimpleName();
        }
    }

    public static String lookupClass() {
        Runnable inLambda = () -> {};
        return MethodHandles.lookup().lookupClass().getName() + "," + (MethodHandles.lookup().lookupClass() == Handles.class)
            + "," + Louder.inner() + "," + (inLambda != null);
    }

    public static String findStatic() throws Throwable {
        MethodHandle h = MethodHandles.lookup()
            .findStatic(Handles.class, "greet", MethodType.methodType(String.class, String.class, boolean.class));
        return (String) h.invoke("bob", true) + "|" + (String) h.invokeExact("ann", false) + "|" + h.invokeWithArguments("cy", false);
    }

    public static String findVirtual() throws Throwable {
        MethodHandle h = MethodHandles.lookup()
            .findVirtual(Handles.class, "shout", MethodType.methodType(String.class, String.class));
        return (String) h.invoke(new Handles(), "a") + "|" + (String) h.invoke((Handles) new Louder(), "b")
            + "|" + (String) h.bindTo(new Handles(7)).invoke("c");
    }

    public static String findConstructor() throws Throwable {
        MethodHandle h = MethodHandles.lookup().findConstructor(Handles.class, MethodType.methodType(void.class, int.class));
        Handles made = (Handles) h.invoke(9);
        return made.shout("d");
    }

    public static String platform() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        int length = (int) lookup.findVirtual(String.class, "length", MethodType.methodType(int.class)).invoke("four");
        int parsed = (int) lookup.findStatic(Integer.class, "parseInt", MethodType.methodType(int.class, String.class)).invoke("12");
        return length + "," + parsed;
    }

    public static String missing() {
        try {
            MethodHandles.lookup().findStatic(Handles.class, "nope", MethodType.methodType(void.class));
            return "found";
        } catch (NoSuchMethodException e) {
            return "NoSuchMethodException";
        } catch (IllegalAccessException e) {
            return "IllegalAccessException";
        }
    }

    public static String unreflect() throws Throwable {
        MethodHandle h = MethodHandles.lookup()
            .unreflect(Handles.class.getDeclaredMethod("greet", String.class, boolean.class));
        return (String) h.invoke("e", false);
    }

    public static String fields() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Handles target = new Handles(5);
        int before = (int) lookup.findGetter(Handles.class, "count", int.class).invoke(target);
        lookup.findSetter(Handles.class, "count", int.class).invoke(target, 8);
        return before + "," + target.count + "," + (String) lookup.findStaticGetter(Handles.class, "tag", String.class).invoke();
    }

    public static String thrown() throws Throwable {
        MethodHandle h = MethodHandles.lookup().findStatic(Handles.class, "fail", MethodType.methodType(int.class, int.class));
        try {
            return "" + (int) h.invoke(4);
        } catch (IllegalStateException e) {
            return "caught " + e.getMessage();
        }
    }

    /** Taken in the class initializer, where libraries usually take theirs. */
    private static final Class<?> AT_INIT = MethodHandles.lookup().lookupClass();

    public static String atClassInit() {
        return AT_INIT.getName();
    }
}
