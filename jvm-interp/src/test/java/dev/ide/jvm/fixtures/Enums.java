package dev.ide.jvm.fixtures;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.function.IntBinaryOperator;
import java.util.stream.IntStream;

/** Interpreted enums handed to the platform's enum machinery. {@code EnumSet}/{@code EnumMap}/{@code
 *  Enum.valueOf} reach for the real enum contract — {@code Class.isEnum()}, a {@code values()} universe,
 *  {@code getDeclaringClass()} — so an interpreted enum's peer has to BE a real enum, one class per enum. */
public final class Enums {
    private Enums() {}

    enum Level { LOW, MEDIUM, HIGH }

    /** Constants with bodies implementing a real interface: they all share the enum's one peer class, which
     *  therefore has to carry every body's implementation of {@code applyAsInt}. */
    enum Op implements IntBinaryOperator {
        PLUS { @Override public int applyAsInt(int a, int b) { return a + b; } },
        TIMES { @Override public int applyAsInt(int a, int b) { return a * b; } };

        @Override public String toString() { return name().toLowerCase(); }
    }

    // ---- enum INSTANCES crossing the bridge -------------------------------------------------------

    public static int setOfTwo() { return EnumSet.of(Level.LOW, Level.HIGH).size(); }

    public static boolean setContainsHigh() { return EnumSet.of(Level.LOW, Level.HIGH).contains(Level.HIGH); }

    public static boolean setContainsMedium() { return EnumSet.of(Level.LOW, Level.HIGH).contains(Level.MEDIUM); }

    public static String setText() { return EnumSet.of(Level.HIGH, Level.LOW).toString(); }

    public static String mapText() {
        EnumMap<Level, String> byLevel = new EnumMap<>(Level.class);
        byLevel.put(Level.HIGH, "h");
        byLevel.put(Level.LOW, "l");
        return byLevel.toString();
    }

    // ---- the enum's CLASS crossing the bridge -----------------------------------------------------

    public static int allOfSize() { return EnumSet.allOf(Level.class).size(); }

    public static String allOfText() { return EnumSet.allOf(Level.class).toString(); }

    public static int constantCount() { return Level.class.getEnumConstants().length; }

    public static boolean classLiteralIsEnum() { return Level.class.isEnum(); }

    public static boolean getClassMatchesClassLiteral() { return Level.LOW.getClass() == Level.class; }

    public static String valueOfReflectively() { return Enum.valueOf(Level.class, "MEDIUM").name(); }

    public static boolean valueOfKeepsIdentity() { return Enum.valueOf(Level.class, "LOW") == Level.LOW; }

    // ---- ordinals and names, read by real code ----------------------------------------------------

    public static int ordinalOfHigh() { return Level.HIGH.ordinal(); }

    public static String nameOfMedium() { return Level.MEDIUM.name(); }

    public static int compareLowToHigh() { return Level.LOW.compareTo(Level.HIGH); }

    // ---- constant bodies reached THROUGH a real interface -----------------------------------------

    public static int reduceWithTimes() { return IntStream.of(2, 3, 4).reduce(1, Op.TIMES); }

    public static int reduceWithPlus() { return IntStream.of(2, 3, 4).reduce(0, Op.PLUS); }

    public static String opText() { return EnumSet.allOf(Op.class).toString(); }
}
