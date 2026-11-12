package dev.ide.jvm.fixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** One method per Java 17 to 21 language or library feature, each returning a string the real JVM also produces. */
public final class Java21 {
    private Java21() {}

    public record Point(int x, int y) {
        public Point {
            if (x < 0) throw new IllegalArgumentException("negative");
        }
        public static Point origin() { return new Point(0, 0); }
        public int sum() { return x + y; }
    }
    public record Pair<A, B>(A first, B second) {}
    public record Line(Point from, Point to) {}
    public record Named(String name, double weight, long id, boolean on, char c, int[] data) {}

    public sealed interface Expr permits Num, Add, Neg {}
    public record Num(int value) implements Expr {}
    public record Add(Expr left, Expr right) implements Expr {}
    public record Neg(Expr inner) implements Expr {}

    public enum Suit { CLUBS, HEARTS, SPADES }

    public sealed static abstract class Animal permits Dog, Cat {}
    public static final class Dog extends Animal {}
    public static final class Cat extends Animal {}

    public static String recordBasics() {
        Point p = new Point(1, 2);
        return p + " " + p.equals(new Point(1, 2)) + " " + (p.hashCode() == new Point(1, 2).hashCode()) + " " + p.sum()
            + " " + Point.origin() + " " + new Pair<>("a", 2) + " " + new Line(p, p).equals(new Line(new Point(1, 2), p));
    }

    public static String recordMixedComponents() {
        Named a = new Named("n", 1.5, 7L, true, 'c', null);
        Named b = new Named("n", 1.5, 7L, true, 'c', null);
        return a + " " + a.equals(b) + " " + (a.hashCode() == b.hashCode()) + " " + a.equals(new Named("n", 2.5, 7L, true, 'c', null));
    }

    public static String recordCompactConstructorThrows() {
        try { new Point(-1, 0); return "no"; } catch (IllegalArgumentException e) { return e.getMessage(); }
    }

    public static String recordReflection() {
        Class<?> c = Point.class;
        StringBuilder sb = new StringBuilder().append(c.isRecord());
        for (var rc : c.getRecordComponents()) sb.append(' ').append(rc.getName()).append(':').append(rc.getType().getSimpleName());
        return sb.toString();
    }

    public static String recordSuperclass() {
        Object p = new Point(1, 2);
        return (p instanceof Record) + " " + p.getClass().getSuperclass().getName();
    }

    public static String instanceofPattern() {
        Object o = "hello";
        if (o instanceof String s && s.length() > 3) return s.toUpperCase();
        return "no";
    }

    public static String recordPatternInstanceof() {
        Object o = new Line(new Point(1, 2), new Point(3, 4));
        if (o instanceof Line(Point(var x1, var y1), Point to) && to.x() > 0) return x1 + "," + y1 + "," + to;
        return "no";
    }

    static int eval(Expr e) {
        return switch (e) {
            case Num n -> n.value();
            case Add(Expr l, Expr r) -> eval(l) + eval(r);
            case Neg(Num(int v)) -> -v;
            case Neg(Expr inner) -> -eval(inner);
        };
    }

    public static String sealedExhaustiveSwitch() {
        return "" + eval(new Add(new Num(2), new Neg(new Add(new Num(3), new Neg(new Num(1))))));
    }

    public static String genericRecordPattern() {
        Object o = new Pair<>("k", 5);
        return switch (o) {
            case Pair<?, ?>(String k, Integer v) when v > 3 -> k + v;
            case Pair<?, ?>(var a, var b) -> "other";
            default -> "none";
        };
    }

    public static String enumSwitchExhaustive() {
        StringBuilder sb = new StringBuilder();
        for (Suit s : Suit.values()) {
            sb.append(switch (s) {
                case CLUBS -> 'c';
                case HEARTS -> 'h';
                case SPADES -> 's';
            });
        }
        return sb.toString();
    }

    public static String enumSwitchStatement() {
        int n = 0;
        for (Suit s : Suit.values()) {
            switch (s) {
                case CLUBS: n += 1; break;
                case HEARTS: n += 10;
                default: n += 100;
            }
        }
        return "" + n;
    }

    public static String sealedClassSwitch() {
        Animal a = new Cat();
        return switch (a) {
            case Dog d -> "dog";
            case Cat c -> "cat";
        };
    }

    public static String sealedReflection() {
        return Expr.class.isSealed() + " " + Expr.class.getPermittedSubclasses().length + " " + Num.class.isSealed();
    }

    public static String switchYieldAndStrings() {
        String day = "TUE";
        int n = switch (day) {
            case "MON", "TUE" -> { int t = day.length(); yield t * 2; }
            case "WED" -> 3;
            default -> 0;
        };
        return "" + n;
    }

    public static String textBlock() {
        String s = """
            Hello,
              "World" \
            end
            """;
        return s.replace("\n", "|");
    }

    public static String stringApis() {
        return "a%sb".formatted(1) + " ab ".strip() + "x".repeat(3) + " ".isBlank() + "a\nb".lines().count()
            + "  x\n  y".stripIndent() + "é".transform(String::length);
    }

    public static String streamApis() {
        List<Integer> l = Stream.of(3, 1, 2).sorted().toList();
        List<Integer> m = Stream.of(1, 2, 3).<Integer>mapMulti((v, sink) -> { if (v != 2) { sink.accept(v); sink.accept(v * 10); } }).toList();
        return l + " " + m + " " + Stream.of("a", "bb").collect(Collectors.teeing(Collectors.counting(), Collectors.joining(), (c, j) -> c + j))
            + Stream.iterate(1, i -> i < 20, i -> i * 2).takeWhile(i -> i < 10).toList();
    }

    public static String sequencedCollections() {
        List<Integer> l = new ArrayList<>(List.of(1, 2, 3));
        l.addFirst(0);
        return l.getFirst() + " " + l.getLast() + " " + l.reversed();
    }

    public static String mathAndBuilder21() {
        return Math.clamp(15, 0, 10) + " " + new StringBuilder().repeat("ab", 2) + " " + "a,b".splitWithDelimiters(",", 0).length;
    }

    public static String virtualThread() throws Exception {
        StringBuilder sb = new StringBuilder();
        Thread t = Thread.ofVirtual().start(() -> sb.append("v"));
        t.join();
        return sb + " " + t.isVirtual();
    }

    public static String nestmatePrivateAccess() {
        class Local { private int secret = 7; private int twice() { return secret * 2; } }
        Inner i = new Inner();
        return new Local().twice() + " " + i.hidden + " " + i.hiddenMethod() + " " + Inner.hiddenStatic();
    }
    private static final class Inner {
        private int hidden = 5;
        private int hiddenMethod() { return hidden + 1; }
        private static int hiddenStatic() { return 9; }
    }

    interface Shape { private int helper() { return 4; } default int area() { return helper() * 2; } static int unit() { return 1; } }

    public static String interfacePrivateMethods() {
        return new Shape() {}.area() + " " + Shape.unit();
    }

    public static String varAndLambdaVar() {
        var list = new ArrayList<Map.Entry<String, Integer>>();
        list.add(Map.entry("a", 1));
        Function<Integer, Integer> f = (var x) -> x + 1;
        return list.get(0).getKey() + f.apply(1);
    }

    public static String switchNullAndDefault() {
        Object o = null;
        return switch (o) {
            case String s -> "s";
            case null, default -> "nd";
        };
    }

    public static String switchOnNullThrows() {
        Object o = null;
        try {
            return switch (o) { case String s -> "s"; default -> "d"; };
        } catch (NullPointerException e) { return "npe"; }
    }

    public static String primitiveBoxSwitch() {
        Object o = 'x';
        Object l = 5L;
        String a = switch (o) { case Character c when c == 'x' -> "char"; default -> "no"; };
        String b = switch (l) { case Long v -> "long" + v; default -> "no"; };
        return a + b;
    }

    public static String recordInCollections() {
        var set = new java.util.HashSet<Point>(List.of(new Point(1, 1), new Point(1, 1), new Point(2, 2)));
        var map = new java.util.TreeMap<String, Point>();
        map.put("a", new Point(3, 4));
        return set.size() + " " + map + " " + List.of(new Point(5, 6));
    }

    public static String recordImplementsInterface() {
        record Temp(double c) implements Comparable<Temp> {
            public int compareTo(Temp o) { return Double.compare(c, o.c); }
            double f() { return c * 9 / 5 + 32; }
        }
        var l = new ArrayList<>(List.of(new Temp(30), new Temp(10)));
        java.util.Collections.sort(l);
        return l + " " + l.get(0).f();
    }

    public record Faulty(int v) {
        public int v() { throw new IllegalStateException("boom"); }
    }

    /** A record pattern whose accessor throws surfaces as a MatchException carrying the cause. */
    public static String matchExceptionFromAccessor() {
        Object o = new Faulty(1);
        try {
            return switch (o) {
                case Faulty(int v) -> "v" + v;
                default -> "d";
            };
        } catch (MatchException e) {
            return e.getClass().getSimpleName() + ":" + e.getCause().getMessage() + ":" + (e instanceof RuntimeException);
        }
    }

    public static String nestedRecordsAsKeys() {
        var m = new java.util.HashMap<Line, String>();
        m.put(new Line(new Point(1, 2), new Point(3, 4)), "a");
        var set = new java.util.HashSet<Line>(List.of(new Line(new Point(0, 0), new Point(1, 1))));
        set.add(new Line(new Point(0, 0), new Point(1, 1)));
        return m.get(new Line(new Point(1, 2), new Point(3, 4))) + " " + set.size() + " " + new Line(new Point(1, 2), new Point(3, 4));
    }

    public static String recordAccessorReflection() throws Exception {
        Point p = new Point(3, 4);
        StringBuilder sb = new StringBuilder();
        for (var rc : Point.class.getRecordComponents()) sb.append(rc.getName()).append('=').append(rc.getAccessor().invoke(p)).append(' ');
        return sb.toString().trim();
    }

    public static String collectionFactories() {
        String dup;
        try { java.util.Set.of(1, 1); dup = "no"; } catch (IllegalArgumentException e) { dup = "dup"; }
        String nul;
        try { List.of("a", null); nul = "no"; } catch (NullPointerException e) { nul = "npe"; }
        String ro;
        try { List.of(1).add(2); ro = "no"; } catch (UnsupportedOperationException e) { ro = "ro"; }
        return List.of() + " " + List.of(1, 2, 3) + " " + List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12).size() + " "
            + java.util.Set.of("x").size() + " " + Map.of("k", 1).get("k") + " " + Map.of("a", 1, "b", 2).size() + " "
            + Map.entry("e", 5).getValue() + " " + List.copyOf(new ArrayList<>(List.of(7))) + " " + dup + nul + ro;
    }

    public static String optionalAndStreams() throws Exception {
        var in = new java.io.ByteArrayInputStream("hello".getBytes());
        var out = new java.io.ByteArrayOutputStream();
        long n = new java.io.ByteArrayInputStream("abc".getBytes()).transferTo(out);
        String empty;
        try { java.util.Optional.empty().orElseThrow(); empty = "no"; } catch (java.util.NoSuchElementException e) { empty = "nse"; }
        return java.util.Optional.empty().isEmpty() + " " + java.util.Optional.of(1).orElseThrow() + " " + empty + " "
            + new String(in.readAllBytes()) + " " + n + out;
    }

    public static String sequencedEdges() {
        List<String> l = new ArrayList<>(List.of("a", "b", "c"));
        String first = l.removeFirst();
        String last = l.removeLast();
        l.addLast("z");
        String empty;
        try { new ArrayList<String>().getFirst(); empty = "no"; } catch (java.util.NoSuchElementException e) { empty = "nse"; }
        return first + last + l + empty;
    }

    public static String reversedIsALiveView() {
        List<Integer> list = new ArrayList<>(List.of(1, 2, 3));
        List<Integer> back = list.reversed();
        back.add(0);            // the end of the reversed view is the front of the list
        list.add(9);
        back.set(1, 8);         // back = [9, 3, 2, 1, 0], so index 1 is the 3
        Integer removed = back.remove(0);
        return list + " " + back + " " + removed + " " + back.reversed() + " " + back.getFirst() + back.indexOf(1);
    }

    public static String stripIndentShapes() {
        return ("   a\n     b\n   c".stripIndent() + "|" + "  a\n  b\n".stripIndent() + "|" + " a  \n\n   b\n ".stripIndent() + "|"
            + "\tx\r\n\ty".stripIndent() + "|" + "".stripIndent() + "|" + "one".stripIndent()).replace("\n", "/");
    }

    public static String mathClamp() {
        String bad;
        try { Math.clamp(1, 5, 2); bad = "no"; } catch (IllegalArgumentException e) { bad = "iae"; }
        return Math.clamp(-3L, 0, 10) + " " + Math.clamp(7L, 0L, 5L) + " " + Math.clamp(2.5, 0.0, 1.0) + " " + Math.clamp(0.5f, 1f, 2f) + " " + bad;
    }

    public static String virtualThreadExecutor() throws Exception {
        var sum = new java.util.concurrent.atomic.AtomicInteger();
        List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 1; i <= 20; i++) {
                int n = i;
                results.add(executor.submit(() -> { sum.addAndGet(n); return n * n; }));
            }
        }
        int squares = 0;
        for (var f : results) squares += f.get();
        String rejected;
        var closed = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        closed.close();
        try { closed.execute(() -> {}); rejected = "no"; } catch (java.util.concurrent.RejectedExecutionException e) { rejected = "rejected"; }
        return sum.get() + " " + squares + " " + rejected + " " + closed.isTerminated();
    }

    public static String threadBuilders() throws Exception {
        var seen = java.util.Collections.synchronizedList(new ArrayList<String>());
        Thread a = Thread.ofVirtual().name("w-", 0).unstarted(
            () -> seen.add(Thread.currentThread().getName() + Thread.currentThread().isVirtual()));
        boolean aliveBefore = a.isAlive();
        a.start();
        a.join();
        var factory = Thread.ofVirtual().name("f-", 5).factory();
        Thread b = factory.newThread(() -> seen.add(Thread.currentThread().getName()));
        b.start();
        b.join();
        Thread c = Thread.ofPlatform().name("plat").daemon(true).start(
            () -> seen.add(Thread.currentThread().getName() + Thread.currentThread().isVirtual() + Thread.currentThread().isDaemon()));
        c.join();
        Thread d = Thread.startVirtualThread(() -> seen.add("d"));
        d.join();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            pool.submit(() -> seen.add("pooled"));
        }
        return aliveBefore + " " + seen + " " + a.isVirtual() + b.isVirtual() + c.isVirtual() + d.isVirtual() + a.isDaemon()
            + Thread.currentThread().isVirtual();
    }

    public static String enhancedInstanceofGeneric() {
        List<?> xs = List.of("a", 1, 2.0);
        int n = 0;
        for (Object x : xs) if (x instanceof Number num) n += num.intValue();
        return "" + n;
    }
}
