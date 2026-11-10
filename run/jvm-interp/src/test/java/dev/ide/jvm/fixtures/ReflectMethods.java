package dev.ide.jvm.fixtures;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Reflection over an interpreted class's METHODS and supertypes: what hamcrest's TypeSafeMatcher does to find
 *  its item type (walk getClass() up through getSuperclass(), reading getDeclaredMethods()), and Method.invoke. */
public final class ReflectMethods {
    private ReflectMethods() {}

    public abstract static class Matcher<T> {
        protected abstract boolean matchesSafely(T item);

        /** hamcrest's ReflectiveTypeFinder: the parameter type of the first non-synthetic matchesSafely(x). */
        public final Class<?> expectedType() {
            for (Class<?> c = getClass(); c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("matchesSafely") && m.getParameterTypes().length == 1 && !m.isSynthetic()) {
                        return m.getParameterTypes()[0];
                    }
                }
            }
            throw new Error("Cannot determine correct type for matchesSafely() method.");
        }
    }

    public static class EmptyString extends Matcher<String> {
        @Override protected boolean matchesSafely(String item) { return item.isEmpty(); }
    }

    /** Declares nothing itself: the type is found on its superclass. */
    public static class StillEmptyString extends EmptyString {}

    public static String expectedTypes() {
        return new EmptyString().expectedType().getName() + "," + new StillEmptyString().expectedType().getName();
    }

    public static String superChain() {
        return StillEmptyString.class.getSuperclass().getName() + "," + Matcher.class.getSuperclass().getName()
            + "," + Runnable.class.getSuperclass() + "," + Named.class.getInterfaces().length;
    }

    public interface Greets { String greet(String who); }

    public static class Greeter implements Greets {
        @Override public String greet(String who) { return "hi " + who; }
        public static int twice(int x) { return 2 * x; }
        public void boom() { throw new IllegalStateException("boom"); }
    }

    public static class LoudGreeter extends Greeter {
        @Override public String greet(String who) { return "HI " + who; }
    }

    public static class Named extends Greeter implements Greets, Runnable {
        @Override public void run() {}
    }

    /** Invoked on a subclass instance, the method found on the base runs the override, as virtual dispatch does. */
    public static String invokeVirtual() throws Exception {
        Method m = Greeter.class.getMethod("greet", String.class);
        return (String) m.invoke(new LoudGreeter(), "bob") + "|" + m.invoke(new Greeter(), "ann");
    }

    public static int invokeStatic() throws Exception {
        return (Integer) Greeter.class.getMethod("twice", int.class).invoke(null, 21);
    }

    public static String invokeWrapsFailure() {
        try {
            Greeter.class.getMethod("boom").invoke(new Greeter());
            return "no throw";
        } catch (InvocationTargetException e) {
            return e.getCause().getClass().getName() + ": " + e.getCause().getMessage();
        } catch (Exception e) {
            return "wrong: " + e;
        }
    }

    /** A method that is not there is still NoSuchMethodException, so a probe's fallback still runs. */
    public static String missingMethod() {
        try {
            Greeter.class.getMethod("absent");
            return "found";
        } catch (NoSuchMethodException e) {
            return "NoSuchMethodException";
        }
    }
}
