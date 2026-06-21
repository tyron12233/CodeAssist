package dev.ide.build.fixtures;

/**
 * Hand-shaped fixtures for {@code ArtAbsentApiScannerTest}. Each exercises one reference *position* so the
 * test can assert the scanner's load-bearing-vs-advisory classification against real compiled bytecode.
 * They reference {@link java.lang.StackWalker} (present on the build JDK, absent on ART) exactly as the
 * Eclipse classes that motivated the scanner do.
 */
public final class Fixtures {

    /** STATIC field of an absent type — the load-bearing `<clinit>` failure (the Status/InternalPlatform shape). */
    public static final class LoadBearingStatic {
        public static final StackWalker WALKER =
                StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    }

    /** INSTANCE field of an absent type — lazily reached on first field access (advisory, not a build break). */
    public static final class InstanceFieldHolder {
        public StackWalker walker;
    }

    /** Absent type used only inside a method body — lazily reached on first call (advisory). */
    public static final class ColdPathMethod {
        public Class<?> caller() {
            return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass();
        }
    }

    /** No absent-type reference at all — must produce zero findings. */
    public static final class CleanClass {
        public String hello() {
            return "hi";
        }
    }

    /** A plain base class used to exercise SUPERTYPE detection with a synthetic denylist entry. */
    public static class SyntheticBase {
    }

    /** Extends {@link SyntheticBase} — load-bearing when SyntheticBase is on the denylist. */
    public static final class SubclassOfFlagged extends SyntheticBase {
    }

    private Fixtures() {
    }
}
