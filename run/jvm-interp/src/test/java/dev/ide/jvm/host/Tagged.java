package dev.ide.jvm.host;

/** A real interface an interpreted object implements and returns across the bridge — the shape of Compose's
 *  {@code DisposableEffectResult} (an inlined {@code onDispose { }} builds an interpreted implementation of it
 *  that the real runtime then casts). Bridged (in {@code dev.ide.jvm.host}), so it is not interpreted. */
public interface Tagged {
    String tag();
}
