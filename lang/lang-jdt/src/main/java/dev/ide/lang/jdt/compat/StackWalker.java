package dev.ide.lang.jdt.compat;

/**
 * An ART-safe stand-in for {@link java.lang.StackWalker}.
 *
 * <p>{@code java.lang.StackWalker} was added in Java 9 and exists on every JVM, but it is
 * <em>absent from Android's ART</em> at our {@code minSdk} (it is not in {@code android.jar} below
 * very recent API levels and is not desugared) and it <em>cannot be stubbed</em>, because
 * application classes may not live in the {@code java.*} namespace on ART. Eclipse's
 * {@code org.eclipse.core.runtime.Status} and {@code org.eclipse.core.internal.runtime.InternalPlatform}
 * each hold a {@code static StackWalker} field, initialised at class load via
 * {@code StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)}. Because {@code Status} is
 * one of the most widely-used Eclipse types, that {@code <clinit>} resolves {@code StackWalker} eagerly
 * on-device and surfaces as an uncatchable {@link LinkageError} that disables editor analysis entirely
 * (the {@code NoClassDefFoundError: java.lang.StackWalker$Option} seen at {@code InternalPlatform.<clinit>}).
 *
 * <p>The build therefore relocates those references to this class (see buildSrc {@code RelocateTypesInJar}
 * and {@code :ide-android}'s {@code relocateCoreRuntimeForArt} / {@code relocateEquinoxCommonForArt}),
 * exactly as ecj's {@code Runtime$Version} reference is relocated to {@link RuntimeVersion}. Only the
 * surface those call sites use is implemented: the static {@link #getInstance(Option)} factory, the
 * {@link Option} enum (with {@code RETAIN_CLASS_REFERENCE}) and the instance {@link #getCallerClass()}.
 *
 * <p>{@link #getCallerClass()} is a <em>best-effort</em> reconstruction from a throwable's stack trace
 * rather than a true caller walk. The two call sites ({@code Status.info/warning/error} and
 * {@code ILog.get}) both wrap the call in {@code try/catch (IllegalCallerException)} and tolerate an
 * imprecise or {@code null} caller (they fall back to a class-name-derived id), so this never throws —
 * deliberately, since {@code java.lang.IllegalCallerException} is itself unavailable below API 33 and we
 * must not depend on it being present.
 */
public final class StackWalker {

    /** Mirror of {@link java.lang.StackWalker.Option}; only {@code RETAIN_CLASS_REFERENCE} is referenced. */
    public enum Option {
        RETAIN_CLASS_REFERENCE,
        SHOW_REFLECT_FRAMES,
        SHOW_HIDDEN_FRAMES
    }

    private static final StackWalker INSTANCE = new StackWalker();

    private StackWalker() {
    }

    /** Equivalent, for our purposes, to {@code java.lang.StackWalker.getInstance(Option)}. */
    public static StackWalker getInstance(Option option) {
        return INSTANCE;
    }

    /**
     * Best-effort stand-in for {@code java.lang.StackWalker#getCallerClass()}: returns the class that
     * called the method which called this. Both Eclipse call sites invoke it one frame below their own
     * public entry point, so the caller they want is at a fixed depth in a fresh stack trace
     * ([0]=this method, [1]=the Eclipse helper, [2]=the real caller). Never throws and never returns
     * {@code null}; falls back to this class if the caller can't be resolved.
     */
    public Class<?> getCallerClass() {
        StackTraceElement[] trace = new Throwable().getStackTrace();
        // Skip frame [0] (this method) and [1] (the Eclipse helper that called us); [2] is the caller.
        for (int i = 2; i < trace.length; i++) {
            String name = trace[i].getClassName();
            if (name == null || name.isEmpty()) continue;
            try {
                return Class.forName(name, false, StackWalker.class.getClassLoader());
            } catch (Throwable ignored) {
                // Class genuinely not loadable here — fall through to the next frame / the fallback.
            }
        }
        return StackWalker.class;
    }
}
