package dev.ide.jvm.fixtures;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/** Language semantics the interpreter once got wrong. Each method returns a String; the test runs it natively
 *  and interpreted and expects the same answer. */
public final class Semantics {
    private Semantics() {}

    static double dz() { return 0.0; }
    static float fz() { return 0.0f; }
    static int minusOne() { return -1; }

    /** `-0.0 == 0.0` under every comparison operator, for both floating types. */
    public static String negativeZero() {
        double a = dz(), b = -dz(); float c = fz(), d = -fz();
        return (a == b) + "," + (a < b) + "," + (a > b) + "," + (a >= b) + "," + (c == d) + "," + (c != d) + "," + (c <= d);
    }

    public static String nan() {
        double n = dz() / dz(); float f = fz() / fz();
        return (n == n) + "," + (n < 1) + "," + (n > 1) + "," + (n != n) + "," + (f >= 1) + "," + (f <= 1);
    }

    /** A primitive array a collection keeps is the same array the program holds. */
    public static String arrayKeptByAList() {
        int[] a = new int[3];
        List<int[]> l = new ArrayList<>();
        l.add(a);
        a[0] = 7;
        int[] back = l.get(0);
        back[1] = 9;
        return back[0] + "," + a[1] + "," + (back == a);
    }

    /** `Arrays.asList` is a view: writes go through in both directions. */
    public static String asListIsAView() {
        Integer[] arr = { 3, 1, 2 };
        List<Integer> view = Arrays.asList(arr);
        Collections.reverse(view);
        String reversed = Arrays.toString(arr);
        view.set(0, 99);
        arr[2] = 42;
        String[] names = { "b", "a" };
        Collections.sort(Arrays.asList(names));
        return reversed + "|" + arr[0] + "|" + view.get(2) + "|" + view + "|" + names[0];
    }

    public static String byteBufferWrap() {
        byte[] b = new byte[4];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b);
        bb.putInt(0x01020304);
        b[0] = 9;
        return b[1] + "," + b[3] + "," + bb.get(0);
    }

    public static String mapOfArrays() {
        HashMap<String, int[][]> m = new HashMap<>();
        m.put("k", new int[2][2]);
        m.get("k")[1][0]++;
        int[][] v = m.get("k");
        v[0][1] = 5;
        return m.get("k")[1][0] + "," + m.get("k")[0][1] + "," + Arrays.deepToString(v);
    }

    public static String arrayTypeChecks() {
        Object ints = new int[3], strings = new String[2], grid = new int[2][3], objects = new Object[1];
        Object runnables = new Runnable[1];
        return (ints instanceof int[]) + "," + (ints instanceof Object[]) + "," + (ints instanceof long[])
            + "," + (strings instanceof Object[]) + "," + (strings instanceof String[]) + "," + (strings instanceof Integer[])
            + "," + (grid instanceof int[][]) + "," + (grid instanceof Object[]) + "," + (grid instanceof int[])
            + "," + (objects instanceof String[]) + "," + (runnables instanceof Object[]) + "," + (runnables instanceof Runnable[])
            + "," + (runnables instanceof Comparable[]) + "," + (objects instanceof Cloneable) + "," + (ints instanceof java.io.Serializable)
            + "," + (runnables instanceof Cloneable) + "," + ((int[]) ints).clone().length + "," + ((Object[]) runnables).clone().length;
    }

    public static String badArrayCast() {
        Object ints = new int[1], objects = new Object[1];
        StringBuilder sb = new StringBuilder();
        try { long[] l = (long[]) ints; sb.append("no").append(l.length); } catch (ClassCastException e) { sb.append("cce"); }
        try { String[] s = (String[]) objects; sb.append("no").append(s.length); } catch (ClassCastException e) { sb.append(",cce"); }
        return sb.toString();
    }

    static class Animal {} static final class Dog extends Animal {} static final class Cat extends Animal {}

    public static String arrayStore() {
        StringBuilder sb = new StringBuilder();
        try { Object[] a = new String[1]; a[0] = Integer.valueOf(1); sb.append("no"); } catch (ArrayStoreException e) { sb.append("ase"); }
        try { Animal[] a = new Dog[1]; a[0] = new Cat(); sb.append(",no"); } catch (ArrayStoreException e) { sb.append(",ase"); }
        try { Object[] a = new Number[1]; a[0] = "s"; sb.append(",no"); } catch (ArrayStoreException e) { sb.append(",ase"); }
        Animal[] ok = new Animal[2]; ok[0] = new Dog(); ok[1] = null;
        Object[] any = new Object[2]; any[0] = new Cat(); any[1] = "s";
        Runnable[] rs = new Runnable[1]; rs[0] = () -> {};
        return sb + "," + (ok[0] instanceof Dog) + "," + any[1] + "," + (rs[0] != null);
    }

    public static String narrowingStores() {
        byte[] b = new byte[1]; short[] s = new short[1]; char[] c = new char[1]; boolean[] z = { true };
        b[0] = (byte) 200; s[0] = (short) 70000; c[0] = (char) 65601; b[0] += 100;
        long[] l = { Long.MAX_VALUE }; float[] f = { 1.5f }; double[] d = { 2.5 };
        l[0]++; f[0] *= 2; d[0] /= 2;
        return b[0] + "," + s[0] + "," + (int) c[0] + "," + z[0] + "," + l[0] + "," + f[0] + "," + d[0];
    }

    public static String negativeArraySize() {
        try { int[] a = new int[minusOne()]; return "no:" + a.length; } catch (NegativeArraySizeException e) { return "caught:" + e.getMessage(); }
    }

    static int deep(int n) { return n == 0 ? 0 : 1 + deep(n - 1); }

    public static String stackOverflow() {
        try { return "no:" + (deep(50_000_000) > 0); } catch (StackOverflowError e) { return "caught"; }
    }

    static final class Lit { static final String AB = "ab"; static String ab() { return "ab"; } }

    public static String internedLiterals() {
        String a = "ab", c = new String("ab");
        return (a == Lit.ab()) + "," + (a == Lit.AB) + "," + (a == c) + "," + (a == c.intern()) + "," + (a == ("a" + "b"));
    }

    static class Shadow { int v = 1; int base() { return v; } }
    static final class Shadowing extends Shadow { int v = 2; int mine() { return v; } }

    /** A field a subclass declares under its superclass's name is a second field. */
    public static String shadowedField() {
        Shadowing s = new Shadowing();
        Shadow asBase = s;
        asBase.v += 10;
        return s.base() + "," + s.mine() + "," + asBase.v + "," + s.v;
    }

    static final class Plain {}
    static class Failure extends RuntimeException { Failure(String m) { super(m); } }

    /** `Object.toString()` and `Throwable.toString()` name the program's class. */
    public static String defaultToString() {
        Plain p = new Plain();
        Failure f = new Failure("boom");
        String viaPlatform = String.valueOf(p), list = List.of(f).toString();
        return p.toString().startsWith("dev.ide.jvm.fixtures.Semantics$Plain@") + "," + viaPlatform.equals(p.toString())
            + "," + f + "," + list + "," + new Failure(null) + "," + ("" + f);
    }

    static final class Counter { static int hits; static long total; static volatile int flag; int n; long sum; double avg; boolean on; String tag; volatile int v; }

    public static String fieldKinds() {
        Counter c = new Counter();
        for (int i = 0; i < 5; i++) { c.n += i; c.sum += 1L << 40; c.avg += 0.5; c.on = !c.on; c.tag = c.tag + i; c.v++; Counter.hits++; Counter.total -= 3; Counter.flag += 2; }
        return c.n + "," + c.sum + "," + c.avg + "," + c.on + "," + c.tag + "," + c.v + "," + Counter.hits + "," + Counter.total + "," + Counter.flag;
    }

    public static String switches() {
        StringBuilder sb = new StringBuilder();
        for (int k : new int[] { -5, 0, 1, 2, 3, 100000, Integer.MIN_VALUE, Integer.MAX_VALUE }) {
            switch (k) { case 0: sb.append('a'); break; case 1: sb.append('b'); break; case 2: sb.append('c'); break; default: sb.append('?'); }
            switch (k) { case -5: sb.append('m'); break; case 100000: sb.append('k'); break; case Integer.MAX_VALUE: sb.append('x'); break; default: sb.append('.'); }
        }
        return sb.toString();
    }
}
