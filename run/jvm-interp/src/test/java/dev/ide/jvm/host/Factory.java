package dev.ide.jvm.host;

import java.util.function.Supplier;

/** A real (bridged) API that invokes a supplied lambda and CASTS its result to a real interface — mirroring
 *  {@code DisposableEffectImpl.onRemembered}, which calls the effect lambda and stores the result as a
 *  {@code DisposableEffectResult}. The {@code return s.get()} emits a {@code checkcast Tagged}, so a raw
 *  interpreted object crossing back from the lambda proxy would fail here (the reported ClassCastException). */
public final class Factory {
    private Factory() {}

    public static Tagged make(Supplier<Tagged> s) {
        return s.get();
    }
}
