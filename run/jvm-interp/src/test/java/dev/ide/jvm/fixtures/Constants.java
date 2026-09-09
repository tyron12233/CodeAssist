package dev.ide.jvm.fixtures;

/** {@code static final} constants of each primitive kind plus a String. javac stores these in the
 *  {@code ConstantValue} attribute (the JVM assigns them at class preparation, with no {@code <clinit>}
 *  assignment), so reading them by name exercises the VM honoring that attribute — the path a class compiled
 *  against a non-final field takes when the provider declares it final (e.g. a library reading a regenerated R). */
public final class Constants {
    private Constants() {}

    public static final int INT = 17;
    public static final long LONG = 0x1_0000_0000L;
    public static final boolean BOOL = true;
    public static final char CH = 'Z';
    public static final String STR = "hi";
}
