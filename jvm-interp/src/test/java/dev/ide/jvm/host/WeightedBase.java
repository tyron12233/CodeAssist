package dev.ide.jvm.host;

/** A real abstract supertype that implements {@link Weighted} WITHOUT implementing {@link Weighted#weight()}
 *  — so an interpreted subclass's override is the only implementation, even though that subclass names only
 *  this class as its supertype and never the interface. Mirrors {@code GoogleFontImpl : AndroidFont(…)},
 *  where {@code AndroidFont implements Font} but declares neither {@code weight} nor {@code style}. */
public abstract class WeightedBase implements Weighted {
    public String label() {
        return "weighted";
    }

    /** Platform code asking the object for the interface method, the way Compose's FontMatcher does. */
    public static int ask(Weighted w) {
        return w.weight();
    }
}
