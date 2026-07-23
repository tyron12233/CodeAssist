package dev.ide.jvm.fixtures;

import dev.ide.jvm.host.Counter;
import dev.ide.jvm.host.Eager;
import dev.ide.jvm.host.Shape;
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

    /** Calls the real template method, which dispatches back to the interpreted override. */
    public static int describeTriangle() { return new Triangle().describe(); }

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
}
