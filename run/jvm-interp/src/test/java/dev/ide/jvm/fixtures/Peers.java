package dev.ide.jvm.fixtures;

import dev.ide.jvm.host.Counter;
import dev.ide.jvm.host.Eager;
import dev.ide.jvm.host.Shape;
import dev.ide.jvm.host.Weighted;
import dev.ide.jvm.host.WeightedBase;
import dev.ide.jvm.host.Widget;
import java.util.Arrays;
import java.util.function.IntUnaryOperator;
import java.util.stream.IntStream;

/** Interpreted classes that extend a real supertype or implement a real interface, so platform code can hold
 *  and invoke them through a generated peer. */
public final class Peers {
    private Peers() {}

    static class Triangle extends Shape {
        @Override public int sides() { return 3; }
        /** Reads the inherited static array + scalar declared on the real super. javac emits these getstatics
         *  with Triangle (interpreted) as the owner, so the VM must fall through to the real super. */
        int stateSum() {
            int s = STATE_SET.length + BASE;
            for (int v : STATE_SET) s += v;
            return s;
        }
    }

    static class Square extends Shape {
        @Override public int sides() { return 4; }
        @Override public int describe() { return super.describe() + 1; }
    }

    /** A concrete-class peer whose interpreted override throws — reached through the real template method
     *  {@link Shape#describe()}, so platform code invokes the throwing override across the generated-subclass
     *  peer dispatch (the surface the peer-exception sink must degrade instead of crashing). */
    static class Boomer extends Shape {
        @Override public int sides() { throw new RuntimeException("boom-sides"); }
    }

    /** Implements an interface method its real abstract super left unimplemented, without naming the
     *  interface itself — the peer must still carry {@code weight()}. */
    static class Heavy extends WeightedBase {
        @Override public int weight() { return 700; }
    }

    static class Doubler implements IntUnaryOperator {
        @Override public int applyAsInt(int v) { return v * 2; }
    }

    static class Button extends Widget {
        Button(String name) { super(name, 10); }
        @Override protected String render() { return "button"; }
    }

    static class Greeter extends Eager {
        private final String suffix = "!";
        @Override protected String tag() { return "greeter" + (suffix == null ? "?" : suffix); }
    }

    static class Tally extends Counter {
        Tally(int start) { this.count = start; }
        @Override protected int bump() { return 5; }
        int current() { return this.count; }
    }

    /** Hands platform code an interpreted object typed as the INTERFACE its real abstract super left
     *  unimplemented, so the caller invokes {@code weight()} on the generated peer. */
    public static Weighted makeHeavy() { return new Heavy(); }

    /** The same override reached through real code rather than by the test calling the peer directly. */
    public static int askHeavy() { return WeightedBase.ask(new Heavy()); }

    /** Calls the real template method, which dispatches back to the interpreted override. */
    public static int describeTriangle() { return new Triangle().describe(); }

    /** Real template method calling an interpreted override that throws (see {@link Boomer}). */
    public static int describeBoomThroughRealCode() { return new Boomer().describe(); }

    /** The override calls super, which reaches the real implementation through the peer. */
    public static int describeSquare() { return new Square().describe(); }

    /** Returns an interpreted instance as its real supertype for platform code to drive. */
    public static Shape makeSquare() { return new Square(); }

    /** Passes an interpreted interface implementation to a platform API that invokes it. */
    public static int mapDoubled(int n) {
        return IntStream.range(0, n).map(new Doubler()).sum();
    }

    /** Returns an interpreted interface implementation as its real interface type, for a caller to invoke the
     *  overridden method and a non-overridden default (compose/andThen) directly. */
    public static IntUnaryOperator doubler() { return new Doubler(); }

    /** Threads the interpreted constructor's arguments into a real superclass that has no no-arg constructor;
     *  the real label() then reads that state and calls the interpreted render(). */
    public static String makeButtonLabel(String name) {
        Widget w = new Button(name);
        return w.label();
    }

    /** Passes an interpreted array to a platform API (outbound array conversion). */
    public static int sumViaStream(int n) {
        int[] xs = new int[n];
        for (int i = 0; i < n; i++) xs[i] = i * i;
        return Arrays.stream(xs).sum();
    }

    /** Uses an array returned by a platform call (inbound array handling). */
    public static int splitAndMeasure(String s) {
        String[] parts = s.split(",");
        int sum = parts.length;
        for (String p : parts) sum += p.length();
        return sum;
    }

    /** Checks an interpreted instance against a real interface reached through its bridged super. */
    public static boolean triangleIsSized() {
        return new Triangle() instanceof dev.ide.jvm.host.Sized;
    }

    /** Reads and writes a protected field declared by the real super, both directly and through a real method. */
    public static int tallyReport(int start) {
        Tally t = new Tally(start);
        return t.report() + t.current();
    }

    /** The real super constructor calls the interpreted override before the subclass initializers ran (the
     *  constructor-time virtual dispatch a View subclass relies on); the second part shows the initialized
     *  behavior after construction. */
    public static String eagerGreeting() {
        Greeter g = new Greeter();
        return g.greeting() + "/" + g.tag();
    }

    /** Reads a static array + scalar inherited from the real super, via a subclass-owner getstatic. */
    public static int triangleStateSum() { return new Triangle().stateSum(); }

    /** Passes a lambda whose result is an interpreted implementation of a real interface to a bridged API that
     *  invokes the lambda and CASTS the result (the `Factory.make` → `s.get()` cast) — the Compose
     *  `DisposableEffect { … onDispose { } }` shape. The lambda's SAM result must cross the bridge as a real
     *  peer, or the cast in `make` throws ClassCastException. */
    public static String suppliedTagViaHost() {
        dev.ide.jvm.host.Tagged t = dev.ide.jvm.host.Factory.make(
            () -> new dev.ide.jvm.host.Tagged() {
                @Override public String tag() { return "made"; }
            });
        return t.tag();
    }
}
