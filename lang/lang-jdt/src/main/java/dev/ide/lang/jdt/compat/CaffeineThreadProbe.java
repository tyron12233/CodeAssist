package dev.ide.lang.jdt.compat;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * ART-safe stand-in for caffeine's {@code StripedBuffer} thread-probe, a per-thread pseudo-random {@code int}
 * used to pick a buffer stripe (a copy of {@code java.util.concurrent.atomic.Striped64}'s probe).
 *
 * <p>Caffeine reads/writes the probe by reflecting the JDK-internal field {@code java.lang.Thread
 * .threadLocalRandomProbe} via {@code Unsafe} ({@code StripedBuffer.<clinit>} computes its offset, and
 * {@code getProbe}/{@code advanceProbe} read/write it on {@code Thread.currentThread()}). That field is a
 * non-SDK member on Android; on a strict device the reflective offset lookup fails, so {@code StripedBuffer}
 * fails to initialize and every bounded caffeine cache dies with {@code NoClassDefFoundError: BoundedBuffer}.
 * That is the on-device KSP crash — KSP2's standalone Analysis API ({@code KotlinStandaloneJvmDependenciesIndex})
 * is the first code to build a {@code maximumSize} (bounded) cache, so it is the first to reach the probe path;
 * the editor's Kotlin backend never does, which is why the editor works on device but KSP did not.
 *
 * <p>{@code CaffeineStripedBufferArtPass} (in {@code build-logic}) redirects {@code StripedBuffer}'s two probe
 * methods here and drops the {@code <clinit>} offset reflection, so the probe lives in a {@link ThreadLocal}
 * and the JDK-internal {@code Thread} field is never touched. The probe's only contract is "a nonzero,
 * well-distributed, per-thread {@code int} that {@code advanceProbe} evolves" — a thread-local holder satisfies
 * it identically. Like the other {@code dev.ide.lang.jdt.compat} shims this rides the {@code dev.ide.kotlinc-art}
 * instrumentation (scope = ALL); desktop keeps the real {@code StripedBuffer}.
 */
public final class CaffeineThreadProbe {

    private CaffeineThreadProbe() {}

    /** Hands each thread a distinct nonzero seed — mirrors {@code ThreadLocalRandom.localInit()}'s guarantee
     *  of a nonzero initial probe, striding by the golden ratio so the stripes spread across threads. */
    private static final AtomicInteger SEEDER = new AtomicInteger();

    private static final ThreadLocal<int[]> PROBE = new ThreadLocal<int[]>() {
        @Override protected int[] initialValue() {
            int p = SEEDER.addAndGet(0x9E3779B9);
            return new int[] { (p == 0) ? 1 : p };
        }
    };

    /** The current thread's probe (always nonzero, so caffeine never takes its lazy-init-on-zero branch). */
    public static int getProbe() {
        return PROBE.get()[0];
    }

    /** xorshift the probe and store it back for this thread (caffeine's contention re-hash), returning it. */
    public static int advanceProbe(int probe) {
        probe ^= probe << 13;
        probe ^= probe >>> 17;
        probe ^= probe << 5;
        PROBE.get()[0] = probe;
        return probe;
    }
}
