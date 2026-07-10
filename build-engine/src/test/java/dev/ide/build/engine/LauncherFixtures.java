package dev.ide.build.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Java fixtures for {@link ReflectiveMainLauncher} tests — one nested class per {@code main} shape the
 * launcher must handle (Java is used so each bytecode shape is expressed unambiguously, independent of what
 * a given kotlinc version emits). Each runnable {@code main} appends to {@link #EVENTS} so a test can assert
 * exactly which entry point ran.
 */
public final class LauncherFixtures {
    private LauncherFixtures() {}

    public static final List<String> EVENTS = new ArrayList<>();

    static void record(String e) { EVENTS.add(e); }

    private static String join(String[] a) { return String.join(",", a); }

    /** static main(String[]) — Java main; Kotlin fun main(args); @JvmStatic main(args) in object/companion. */
    public static final class StaticArgs {
        public static void main(String[] a) { record("staticArgs:" + join(a)); }
    }

    /** static main() with NO (String[]) bridge — the @JvmStatic fun main() (no-arg) shape a VM launcher can't start. */
    public static final class StaticNoArg {
        public static void main() { record("staticNoArg"); }
    }

    /** instance main(String[]) — the IDE's instance-main convenience, arg-taking. */
    public static final class InstanceArgs {
        public void main(String[] a) { record("instanceArgs:" + join(a)); }
    }

    /** instance main() — a plain class { fun main() } (the InstanceMainRunTest shape). */
    public static final class InstanceNoArg {
        public void main() { record("instanceNoArg"); }
    }

    /** No runnable main at all → resolve returns null. */
    public static final class NoMain {
        public void notMain() {}
    }

    /** A non-void main is not an entry point → resolve returns null. */
    public static final class NonVoidMain {
        public static int main(String[] a) { return a.length; }
    }

    /** Both an instance main(String[]) and a static main(): static must win (records "staticNoArg-wins"). */
    public static final class StaticBeatsInstance {
        public void main(String[] a) { record("BAD-instanceArgs"); }
        public static void main() { record("staticNoArg-wins"); }
    }

    /** Base with a static main(String[]) — inherited by {@link Sub}. */
    public static class Base {
        public static void main(String[] a) { record("base:" + join(a)); }
    }

    /** Declares no main; its static main(String[]) is inherited from {@link Base} (VM-launcher parity). */
    public static final class Sub extends Base {}
}
