package dev.ide.jvm.fixtures;

/** Pattern {@code switch}es, which compile to {@code SwitchBootstraps.typeSwitch}/{@code enumSwitch} call
 *  sites rather than a table over an int. */
public final class PatternSwitch {
    private PatternSwitch() {}

    public enum Color { RED, GREEN, BLUE }

    public sealed interface Shape permits Circle, Square {}
    public record Circle(int radius) implements Shape {}
    public record Square(int side) implements Shape {}

    public static Object sample(int which) {
        return switch (which) {
            case 0 -> "Hello";
            case 1 -> 42;
            case 2 -> null;
            case 3 -> 2.5;
            case 4 -> new Circle(3);
            case 5 -> new Square(4);
            case 6 -> Color.GREEN;
            case 7 -> -7;
            case 8 -> "";
            case 9 -> new int[] {1, 2};
            default -> 'x';
        };
    }

    public static String byType(int which) {
        Object value = sample(which);
        return switch (value) {
            case String s -> "String: " + s;
            case Integer i -> "Integer: " + i;
            case null -> "Null";
            default -> "Other";
        };
    }

    /** A failing guard re-enters the call site at the next label. */
    public static String guarded(int which) {
        Object value = sample(which);
        return switch (value) {
            case String s when s.isEmpty() -> "empty";
            case String s -> "text " + s.length();
            case Integer i when i < 0 -> "negative";
            case Integer i -> "int " + i;
            case int[] a -> "ints " + a.length;
            case Shape shape -> area(shape);
            case Color c -> "color " + c.name();
            default -> "other";
        };
    }

    /** Exhaustive over a sealed interface of interpreted records, with record deconstruction. */
    public static String area(Shape shape) {
        return switch (shape) {
            case Circle(int radius) -> "circle " + 3 * radius * radius;
            case Square(int side) -> "square " + side * side;
        };
    }

    /** An enum selector mixing a constant with a pattern links through {@code enumSwitch}. */
    public static String enumMixed(int ordinal) {
        Color color = ordinal < 0 ? null : Color.values()[ordinal];
        return switch (color) {
            case null -> "none";
            case RED -> "red";
            case Color c when c == Color.BLUE -> "guarded blue";
            case Color c -> "some " + c;
        };
    }

    /** A qualified enum constant among type patterns is an {@code EnumDesc} label of {@code typeSwitch}. */
    public static String qualifiedConstant(int which) {
        Object value = sample(which);
        return switch (value) {
            case Color.RED -> "red";
            case Color.GREEN -> "green";
            case String s -> "string";
            default -> "other";
        };
    }

    public static String stringAndIntConstants(int which) {
        Object value = sample(which);
        if (value instanceof Integer i) {
            return switch (i) {
                case 42 -> "answer";
                case Integer n when n < 0 -> "negative";
                case Integer n -> "int";
            };
        }
        if (value instanceof String s) {
            return switch (s) {
                case "Hello" -> "greeting";
                case String t when t.isEmpty() -> "empty";
                case String t -> "text";
            };
        }
        return "neither";
    }
}
