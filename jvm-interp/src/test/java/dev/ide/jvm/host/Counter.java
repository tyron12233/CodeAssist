package dev.ide.jvm.host;

/** A real supertype with a protected field an interpreted subclass reads and writes directly, and a real
 *  method that reads that field and calls the abstract {@link #bump()} an interpreted subclass overrides. */
public abstract class Counter {
    protected int count;

    public int report() {
        return count + bump();
    }

    protected abstract int bump();
}
