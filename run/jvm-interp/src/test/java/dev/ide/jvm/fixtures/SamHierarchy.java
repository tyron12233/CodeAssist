package dev.ide.jvm.fixtures;

import dev.ide.jvm.host.Registry;
import java.util.Comparator;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** A lambda's type is its functional interface AND every supertype of it: casts and instanceof to a
 *  super-interface, crossing to platform code by a super-interface parameter, `Object`'s methods, and default
 *  methods of the interface, real or interpreted. */
public final class SamHierarchy {
    private SamHierarchy() {}

    /** An explicit cast of a lambda to a SUPERTYPE of its functional interface, through Object so javac emits
     *  the checkcast (the one the Kotlin compiler emits when coercing a LifecycleEventObserver to the
     *  LifecycleObserver parameter of addObserver). */
    public static String castToSuper(String s) {
        UnaryOperator<String> up = v -> v.toUpperCase();
        Object held = up;
        Function<String, String> f = (Function<String, String>) held;
        return f.apply(s);
    }

    public static boolean instanceChecks() {
        UnaryOperator<String> up = v -> v;
        Object held = up;
        return held instanceof UnaryOperator && held instanceof Function
            && !(held instanceof Runnable) && !(held instanceof Comparator);
    }

    /** Register by the marker super-interface, dispatch, remove, dispatch again: only the first event arrives,
     *  and nothing stays registered, so the same object must have crossed both times. */
    public static String observeThenRemove(Registry registry) {
        StringBuilder sink = new StringBuilder();
        Registry.EventObserver observer = e -> sink.append(e).append(';');
        Object held = observer;
        registry.add((Registry.Observer) held);
        registry.dispatch("resumed");
        registry.remove((Registry.Observer) held);
        registry.dispatch("paused");
        return sink + "|registered=" + registry.size() + "|ignored=" + registry.ignored;
    }

    /** Object's methods on a lambda held as an Object, in interpreted code. */
    public static boolean objectMethodsOnLambda() {
        Runnable r = () -> {};
        Object held = r;
        return held.hashCode() == held.hashCode() && held.equals(held) && !held.equals("x") && held.toString() != null;
    }

    /** A default method of a REAL functional interface, invoked on the lambda from interpreted code. */
    public static int reversedComparator() {
        Comparator<String> byLength = (a, b) -> Integer.compare(a.length(), b.length());
        return byLength.reversed().compare("aaa", "b");
    }

    interface Greeter {
        String name();

        default String greet() {
            return "hi " + name();
        }
    }

    /** A default method of an INTERPRETED functional interface, whose body calls back into the lambda. */
    public static String defaultOnInterpretedInterface() {
        Greeter g = () -> "bob";
        return g.greet();
    }
}
