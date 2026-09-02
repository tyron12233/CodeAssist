package dev.ide.jvm.fixtures;

import java.lang.reflect.Array;

/** Arrays whose component type is an INTERPRETED class. {@code Array.newInstance(Segment.class, size)} is the
 *  shape Spring's {@code ConcurrentReferenceHashMap} uses for its segment table before filling it with
 *  {@code new Segment(...)}: there is no real class for an interpreted component type, only a stand-in that
 *  stands for it in reflection, so a real array of it could never hold the program's instances. */
public final class ReflectArray {
    private ReflectArray() {}

    public static class Segment {
        private final int weight;
        public Segment(int weight) { this.weight = weight; }
        public int weight() { return weight; }
    }

    /** Create the table reflectively, fill it with interpreted instances, and read it back. */
    public static int segmentTotal(int size) {
        Segment[] segments = (Segment[]) Array.newInstance(Segment.class, size);
        for (int i = 0; i < segments.length; i++) {
            segments[i] = new Segment(i + 1);
        }
        int total = 0;
        for (Segment segment : segments) {
            total += segment.weight();
        }
        return total;
    }

    /** The same table, written and read through {@link Array} rather than the array opcodes. */
    public static int reflectiveElementAccess(int size) {
        Object segments = Array.newInstance(Segment.class, size);
        for (int i = 0; i < Array.getLength(segments); i++) {
            Array.set(segments, i, new Segment(i + 2));
        }
        int total = 0;
        for (int i = 0; i < Array.getLength(segments); i++) {
            total += ((Segment) Array.get(segments, i)).weight();
        }
        return total;
    }

    /** A two-dimensional reflective array of an interpreted component type. */
    public static int matrixTotal(int rows, int cols) {
        Segment[][] grid = (Segment[][]) Array.newInstance(Segment.class, rows, cols);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                grid[r][c] = new Segment(r * cols + c + 1);
            }
        }
        int total = 0;
        for (Segment[] row : grid) {
            for (Segment segment : row) {
                total += segment.weight();
            }
        }
        return total;
    }

    /** A plain {@code new Segment[rows][cols]} handed to platform code: the real array mirroring it needs a
     *  real array class for the nested interpreted element type. */
    public static int nestedArrayAcrossTheBridge(int rows, int cols) {
        Segment[][] grid = new Segment[rows][cols];
        grid[0][0] = new Segment(7);
        return Array.getLength(grid) * 100 + Array.getLength(grid[0]) * 10 + grid[0][0].weight();
    }

    /** A real component type stays a real array: the elements are platform values throughout. */
    public static String realComponentType(int size) {
        String[] names = (String[]) Array.newInstance(String.class, size);
        for (int i = 0; i < names.length; i++) {
            names[i] = "s" + i;
        }
        return String.join(",", names);
    }

    /** A reflective array of an interpreted component type handed to platform code, which sees the elements
     *  through their real supertype (peers of the interpreted instances). */
    public static int sortedByWeight(int size) {
        Comparable<?>[] boxes = (Comparable<?>[]) Array.newInstance(Box.class, size);
        for (int i = 0; i < boxes.length; i++) {
            boxes[i] = new Box(size - i);
        }
        java.util.Arrays.sort(boxes);
        int packed = 0;
        for (Comparable<?> box : boxes) {
            packed = packed * 10 + ((Box) box).weight();
        }
        return packed;
    }

    /** A class literal for an array of an interpreted type. No real class is named by it, so it is a real
     *  array of the type's stand-in, whose component type is the Class the element's own literal yields. */
    public static boolean arrayClassLiteral() {
        Class<?> arrayClass = Segment[].class;
        return arrayClass.isArray() && arrayClass.getComponentType() == Segment.class;
    }

    /** The literal and the reflective lookup of the same array type must be the same Class. */
    public static boolean arrayClassByName() throws Exception {
        return Class.forName("[Ldev.ide.jvm.fixtures.ReflectArray$Segment;") == Segment[].class;
    }

    /** A reflective array whose component type is ITSELF an array of an interpreted type. */
    public static int nestedReflectiveArray(int rows, int cols) {
        Segment[][] grid = (Segment[][]) Array.newInstance(Segment[].class, rows);
        for (int r = 0; r < rows; r++) {
            grid[r] = new Segment[cols];
            for (int c = 0; c < cols; c++) {
                grid[r][c] = new Segment(r * cols + c + 1);
            }
        }
        int total = 0;
        for (Segment[] row : grid) {
            for (Segment segment : row) {
                total += segment.weight();
            }
        }
        return total;
    }

    /** Interpreted, and NOT trivial: it implements a real interface, so it crosses the bridge as a peer. */
    public static class Box implements Comparable<Box> {
        private final int weight;
        public Box(int weight) { this.weight = weight; }
        public int weight() { return weight; }
        @Override public int compareTo(Box other) { return Integer.compare(this.weight, other.weight); }
    }
}
