package dev.ide.lang.jdt.compat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ART-safe stand-in for {@link java.lang.ClassValue}.
 *
 * <p>{@code java.lang.ClassValue} was added to the JDK in Java 7 but is absent from Android's ART runtime.
 * The IntelliJ platform's {@code com.intellij.util.messages.impl.MethodHandleCache} EXTENDS {@code ClassValue}
 * to memoize a listener's method handles per {@link Class}. ART eager-links a class's superclass at load, so
 * loading {@code MethodHandleCache} throws {@link NoClassDefFoundError NoClassDefFoundError:
 * java.lang.ClassValue} — and because the MessageBus resolves every listener method handle through it, the
 * FIRST PSI-change event (e.g. {@code PsiManagerImpl.dropResolveCaches} during an editor document update)
 * crashes the app.
 *
 * <p>The build relocates {@code MethodHandleCache}'s {@code java/lang/ClassValue} superclass reference to this
 * class (see the {@code ClassValueArtPass} bytecode pass in {@code build-logic}), so on device it extends this
 * shim instead. Desktop keeps the real {@code java.lang.ClassValue}. This mirrors the {@code get} /
 * {@code computeValue} / {@code remove} surface with a {@link ConcurrentHashMap}-backed memoizer.
 *
 * <p>Semantics kept faithful to the contract {@code MethodHandleCache} relies on: {@link #get} lazily calls
 * {@link #computeValue} once per {@link Class} and caches the result (nulls included), and is safe under
 * concurrent access. Like the real {@code ClassValue}, {@link #computeValue} MAY be invoked more than once for
 * the same type under a race — only one result is retained. The one intentional divergence is that the cache
 * holds {@link Class} keys strongly rather than weakly; the listener-interface key set is small and bounded,
 * so this is not a practical leak.
 *
 * @param <T> the memoized value type
 */
public abstract class ClassValue<T> {

    /** Distinguishes "computed a null value" from "not yet computed" ({@link ConcurrentHashMap} rejects nulls). */
    private static final Object NULL_SENTINEL = new Object();

    private final Map<Class<?>, Object> cache = new ConcurrentHashMap<>();

    /** Matches {@code java.lang.ClassValue}'s protected no-arg constructor (subclasses call {@code super()}). */
    protected ClassValue() {}

    /** Computes the value for {@code type}. Invoked at most once per type by {@link #get} in the common case. */
    protected abstract T computeValue(Class<?> type);

    /** The memoized value for {@code type}, computing and caching it on first access. */
    @SuppressWarnings("unchecked")
    public T get(Class<?> type) {
        Object existing = cache.get(type);
        if (existing == null) {
            T computed = computeValue(type);
            Object boxed = (computed == null) ? NULL_SENTINEL : computed;
            Object prev = cache.putIfAbsent(type, boxed);
            existing = (prev != null) ? prev : boxed;
        }
        return existing == NULL_SENTINEL ? null : (T) existing;
    }

    /** Drops the cached value for {@code type}; the next {@link #get} recomputes it. */
    public void remove(Class<?> type) {
        cache.remove(type);
    }
}
