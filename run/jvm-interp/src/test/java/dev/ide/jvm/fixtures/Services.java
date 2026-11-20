package dev.ide.jvm.fixtures;

import java.util.Locale;
import java.util.ResourceBundle;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * What a library finds on its own classpath at startup: the providers of a service it declares (an SLF4J
 * binding), its message catalogue, and a constant it inherits from an interface.
 */
public class Services {
    public interface Greeter {
        String greet();
    }

    public static class English implements Greeter {
        public String greet() {
            return "hello";
        }
    }

    public static class Spanish implements Greeter {
        public String greet() {
            return "hola";
        }
    }

    public static String loaded() {
        StringBuilder out = new StringBuilder();
        for (Greeter g : ServiceLoader.load(Greeter.class)) out.append(g.greet()).append(',');
        out.append(ServiceLoader.load(Greeter.class, Services.class.getClassLoader()).findFirst().map(Greeter::greet).orElse("none"));
        out.append(',').append(ServiceLoader.load(Greeter.class).stream()
            .map(p -> p.type().getSimpleName() + "=" + p.get().greet()).collect(Collectors.joining("+")));
        // A service nobody provides, here or in the platform.
        out.append(',').append(ServiceLoader.load(Unprovided.class).iterator().hasNext());
        return out.toString();
    }

    public interface Unprovided {}

    public static String bundle() {
        ResourceBundle root = ResourceBundle.getBundle("dev.ide.jvm.fixtures.messages", Locale.ROOT);
        ResourceBundle french = ResourceBundle.getBundle("dev.ide.jvm.fixtures.messages", Locale.FRENCH);
        return root.getString("greeting") + "," + root.containsKey("nope") + "," + french.getString("greeting")
            + "," + french.getString("farewell");
    }

    /** Bundles written as classes, with a locale's class over the root's. */
    public static String classBundle() {
        ResourceBundle root = ResourceBundle.getBundle("dev.ide.jvm.fixtures.Texts", Locale.ROOT);
        ResourceBundle french = ResourceBundle.getBundle("dev.ide.jvm.fixtures.Texts", Locale.FRENCH);
        return root.getString("greeting") + "," + root.getObject("count") + "," + french.getString("greeting")
            + "," + french.getString("only") + "," + new java.util.TreeSet<>(french.keySet());
    }

    interface Names {
        String KEY = "k".concat("ey");
        Object TOKEN = new Object();
    }

    interface MoreNames extends Names {}

    static class Named implements MoreNames {}

    static class NamedMore extends Named {}

    /** A constant declared by an interface, read through the classes that inherit it. */
    public static String inheritedConstant() {
        return NamedMore.KEY + "," + Named.KEY + "," + (NamedMore.TOKEN == Names.TOKEN);
    }
}
