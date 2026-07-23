package dev.ide.jvm.host;

/** A real supertype the interpreter does not run (the test configures its package as bridged). Its concrete
 *  {@link #describe()} is a template method that calls the abstract {@link #sides()}, so an interpreted
 *  subclass's override is reached through real code. */
public abstract class Shape implements Sized {
    /** A static field an interpreted subclass reads through a subclass-owner getstatic (as javac emits for an
     *  inherited static), so it must be read from this real super, not the interpreted chain. Mirrors
     *  android.view.View.EMPTY_STATE_SET, whose null read broke BottomNavigationView in the preview. */
    protected static final int[] STATE_SET = {10, 20, 30};
    protected static final int BASE = 7;

    public abstract int sides();

    public int describe() {
        return sides() * 100;
    }

    public String kind() {
        return "shape:" + sides();
    }
}
