package dev.ide.jvm.fixtures;

/**
 * The runtime {@code Class} of the program's own types. A framework reads it to identify a value's type, to
 * match that type against another, and to name it in a message, so it has to be one class per type, the same
 * object the type's class literal yields, and it has to answer to the program's own hierarchy.
 */
public class RuntimeClass {

    public interface Fac<S, T> { T convert(S s); }

    public static class Base { }

    public static class StrToInt extends Base implements Fac<String, Integer> {
        @Override public Integer convert(String s) { return s.length(); }
    }

    public static class Plain { }

    enum Level { LOW, HIGH }

    public static boolean literalIsRuntimeClass() {
        return (Object) new StrToInt().getClass() == (Object) StrToInt.class;
    }

    /** Two types that share every supertype still have their own class. */
    public static boolean unrelatedTypesHaveDistinctClasses() {
        return (Object) new StrToInt().getClass() != (Object) new Plain().getClass();
    }

    public static boolean forNameRoundTrips() throws Exception {
        Object x = new StrToInt();
        return (Object) Class.forName(x.getClass().getName()) == (Object) StrToInt.class;
    }

    public static boolean recognizesItsOwnInstance() {
        Object x = new StrToInt();
        return x.getClass().isInstance(x);
    }

    public static boolean rejectsAnotherTypesInstance() {
        return StrToInt.class.isInstance(new Plain());
    }

    public static boolean baseIsAssignableFromSubclass() {
        return Base.class.isAssignableFrom(StrToInt.class);
    }

    public static boolean subclassIsNotAssignableFromBase() {
        return StrToInt.class.isAssignableFrom(Base.class);
    }

    public static boolean interfaceIsAssignableFromImplementation() {
        return Fac.class.isAssignableFrom(StrToInt.class);
    }

    /** The cast's result is the same object, and still usable as its own type. */
    public static int castKeepsTheInstance() {
        Base b = Base.class.cast(new StrToInt());
        return ((StrToInt) b).convert("abcd");
    }

    public static String castToAnUnrelatedType() {
        try {
            Plain.class.cast(new StrToInt());
            return "<no throw>";
        } catch (ClassCastException e) {
            return "ClassCastException";
        }
    }

    public static String runtimeName()        { return new StrToInt().getClass().getName(); }
    public static String simpleName()         { return new StrToInt().getClass().getSimpleName(); }
    public static String classToString()      { return StrToInt.class.toString(); }
    public static String interfaceToString()  { return Fac.class.toString(); }
    public static String enumName()           { return Level.class.getName(); }
    public static String enumConstantName()   { return Level.LOW.getClass().getName(); }

    /** An anonymous class has no simple name, and a message that quotes one must not invent one. */
    public static String anonymousSimpleName() {
        Fac<String, Integer> f = new Fac<String, Integer>() {
            @Override public Integer convert(String s) { return 0; }
        };
        return "[" + f.getClass().getSimpleName() + "]";
    }
}
