package dev.ide.lang.jdt.compat;

import java.util.Objects;

/**
 * An ART-safe stand-in for {@link java.lang.Runtime.Version}.
 *
 * <p>{@code java.lang.Runtime.Version} was added in Java 9 and exists on every JVM, but it is
 * <em>absent from Android's ART</em> — it is not in {@code android.jar} and not in the runtime — and
 * it <em>cannot be stubbed</em>, because application classes may not live in the {@code java.*}
 * namespace on ART. Eclipse ecj's {@code org.eclipse.jdt.internal.compiler.parser.Parser} references
 * it (only) to compare the requested {@code -source} version against the latest version the compiler
 * supports and emit a "too recent" warning. On ART that reference surfaces as an uncatchable
 * {@link LinkageError} which disables editor analysis entirely.
 *
 * <p>The build therefore relocates ecj's references to this class (see buildSrc
 * {@code RelocateTypesInJar} and {@code :ide-android}). Only the surface the Parser actually uses is
 * implemented — the static {@link #parse(String)} factory, {@link #compareTo(RuntimeVersion)} and
 * {@link #toString()}. Ordering follows the numeric {@code $VNUM} sequence of the
 * <a href="https://openjdk.org/jeps/322">JEP&nbsp;322</a> version-string grammar, which is all the
 * source-version strings ecj parses ({@code "1.8"}, {@code "11"}, {@code "21"}, …) require; any
 * pre-release / build / optional suffix is ignored. Exception behaviour mirrors the real method
 * ({@link NullPointerException} on {@code null}, {@link IllegalArgumentException} on malformed input)
 * so ecj's surrounding {@code catch (Exception)} keeps behaving as designed.
 */
public final class RuntimeVersion implements Comparable<RuntimeVersion> {

    private final int[] vnum;
    private final String raw;

    private RuntimeVersion(int[] vnum, String raw) {
        this.vnum = vnum;
        this.raw = raw;
    }

    /**
     * Parses a version string. Equivalent, for our purposes, to {@code java.lang.Runtime.Version.parse}.
     *
     * @throws NullPointerException     if {@code version} is null
     * @throws IllegalArgumentException if {@code version} is not a valid version string
     */
    public static RuntimeVersion parse(String version) {
        Objects.requireNonNull(version, "version string");
        // Strip the optional -$PRE / +$BUILD / -$OPT suffixes; only $VNUM matters for ordering.
        int cut = version.length();
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c == '-' || c == '+') { cut = i; break; }
        }
        String vstr = version.substring(0, cut);
        if (vstr.isEmpty()) throw invalid(version);

        String[] parts = vstr.split("\\.", -1);
        int[] v = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) throw invalid(version);
            int value;
            try {
                value = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                throw invalid(version);
            }
            if (value < 0) throw invalid(version);
            v[i] = value;
        }
        return new RuntimeVersion(v, version);
    }

    /** Compares the numeric version sequences element-wise; missing trailing elements count as zero. */
    @Override
    public int compareTo(RuntimeVersion other) {
        int n = Math.max(this.vnum.length, other.vnum.length);
        for (int i = 0; i < n; i++) {
            int a = i < this.vnum.length ? this.vnum[i] : 0;
            int b = i < other.vnum.length ? other.vnum[i] : 0;
            if (a != b) return a < b ? -1 : 1;
        }
        return 0;
    }

    /** The original version string, as {@code Runtime.Version#toString} returns it. */
    @Override
    public String toString() {
        return raw;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RuntimeVersion && compareTo((RuntimeVersion) o) == 0;
    }

    @Override
    public int hashCode() {
        int h = 0;
        for (int x : vnum) h = h * 31 + x;
        return h;
    }

    private static IllegalArgumentException invalid(String version) {
        return new IllegalArgumentException("Invalid version string: '" + version + "'");
    }
}
