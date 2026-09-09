package dev.ide.jvm.fixtures;

/** Static state: a field initialized in {@code <clinit>} and a mutable static counter, to prove lazy
 *  class-initialization order and static get/put. */
public final class Statics {
    private Statics() {}

    static final int BASE = 6 * 7;      // computed in <clinit>
    static int counter = BASE;          // seeded from BASE (init order matters)

    public static int base() { return BASE; }

    public static int bumpTwice() {
        counter++;
        counter++;
        return counter;
    }
}
