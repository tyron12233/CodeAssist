package dev.ide.jvm.fixtures;

import java.util.List;

/**
 * What a code generator asks of a class it only holds a literal of: where it is nested (to name it), and a
 * constructor the class keeps private (to build it anyway). kotlinpoet walks the nesting; Room opens a private
 * JavaPoet constructor.
 */
public class ReflectNesting {
    public static class Inner {
        final String value;

        public Inner() {
            this.value = "public";
        }

        private Inner(String name, List<String> rest) {
            this.value = name + rest.size();
        }

        public static class Deep {}
    }

    public static String nesting() {
        Object anonymous = new Object() {};
        return (Inner.class.getEnclosingClass() == ReflectNesting.class)
            + "," + Inner.Deep.class.getDeclaringClass().getName()
            + "," + ReflectNesting.class.getEnclosingClass()
            + "," + Inner.Deep.class.getCanonicalName()
            + "," + Inner.class.isMemberClass()
            + "," + ReflectNesting.class.isMemberClass()
            + "," + anonymous.getClass().getCanonicalName()
            + "," + anonymous.getClass().getDeclaringClass()
            + "," + (anonymous.getClass().getEnclosingClass() == ReflectNesting.class);
    }

    public static String privateConstructor() throws Exception {
        var ctor = Inner.class.getDeclaredConstructor(String.class, List.class);
        ctor.setAccessible(true);
        String built = ctor.newInstance("a", List.of("x", "y")).value;
        String viaPublic = Inner.class.getConstructor().newInstance().value;
        try {
            Inner.class.getConstructor(String.class, List.class);
            return built + "," + viaPublic + ",visible";
        } catch (NoSuchMethodException e) {
            return built + "," + viaPublic + ",hidden," + Inner.class.getDeclaredConstructors().length;
        }
    }
}
