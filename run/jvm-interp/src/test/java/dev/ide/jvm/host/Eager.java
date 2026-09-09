package dev.ide.jvm.host;

/** A real supertype whose constructor calls an overridable method — the {@code View}/{@code TextView} pattern.
 *  A subclass's override therefore runs while the subclass constructor is still inside {@code super()}, before
 *  the subclass's own field initializers; {@link #greeting()} exposes what the constructor-time call produced. */
public abstract class Eager {
    private final String greeting;

    protected Eager() {
        greeting = "hi:" + tag();
    }

    protected abstract String tag();

    public String greeting() {
        return greeting;
    }
}
