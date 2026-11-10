package dev.ide.jvm.fixtures;

import java.lang.annotation.Annotation;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Reflection over an interpreted class's fields, inherited methods, generic signatures and annotations: what Gson
 *  (fields, TypeToken), Retrofit (method and parameter annotations, generic returns) and JUnit do. Each probe
 *  returns a string, so the interpreted run is compared with the real one. */
public final class ReflectMembers {
    private ReflectMembers() {}

    // ---- fields -------------------------------------------------------------------------------------

    public static class Item {
        public static int created = 0;
        public String name = "first";
        public int count = 2;
        public long big = 5L;
        public boolean on = true;
        protected transient Item next;
        private volatile double ratio = 0.5;
    }

    public static String fields() throws Exception {
        TreeSet<String> names = new TreeSet<>();
        for (Field f : Item.class.getDeclaredFields()) {
            names.add(f.getName() + ":" + f.getType().getSimpleName() + ":" + Modifier.toString(f.getModifiers()));
        }
        Item item = new Item();
        Field name = Item.class.getDeclaredField("name");
        Field count = Item.class.getField("count");
        Field ratio = Item.class.getDeclaredField("ratio");
        Field next = Item.class.getDeclaredField("next");
        ratio.setAccessible(true);
        name.set(item, "second");
        count.setInt(item, count.getInt(item) + 40);
        Item.class.getDeclaredField("big").set(item, 7L);
        ratio.set(item, 2.5);
        next.set(item, new Item());
        Item.class.getField("created").set(null, 9);
        return names + "|" + item.name + "," + item.count + "," + item.big + "," + ratio.get(item) + "," + item.on
            + "," + Item.class.getDeclaredField("on").get(item) + "," + (item.next != null) + "," + Item.created
            + "," + (next.getType() == Item.class) + "," + (name.getDeclaringClass() == Item.class)
            + "," + missingField();
    }

    private static String missingField() {
        try {
            Item.class.getDeclaredField("absent");
            return "found";
        } catch (NoSuchFieldException e) {
            return "NoSuchFieldException";
        }
    }

    // ---- inherited public methods -----------------------------------------------------------------

    public static class Base {
        public String hello() { return "base"; }
        protected String hidden() { return "hidden"; }
    }

    public static class Child extends Base implements Runnable {
        public String own() { return "own"; }
        @Override public void run() {}
    }

    public static String methods() throws Exception {
        TreeSet<String> names = new TreeSet<>();
        for (Method m : Child.class.getMethods()) {
            if (m.getDeclaringClass() != Object.class) names.add(m.getName() + "@" + m.getDeclaringClass().getSimpleName());
        }
        boolean objectMethods = false;
        for (Method m : Child.class.getMethods()) if (m.getName().equals("hashCode")) objectMethods = true;
        Method inherited = Child.class.getMethod("hello");
        return names + "|" + objectMethods + "|" + inherited.invoke(new Child()) + "|" + (inherited.getDeclaringClass() == Base.class);
    }

    // ---- generics ---------------------------------------------------------------------------------

    /** Gson's TypeToken: the type argument of the anonymous subclass's generic superclass. */
    public abstract static class Token<T> {
        public final Type type;
        protected Token() {
            this.type = ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
        }
    }

    public static class Box<T extends Number> {
        public Map<String, List<Item>> byName;
        public List<String> names() { return null; }
        public <E extends Comparable<E>> E max(List<? extends E> items, T[] weights) { return null; }
    }

    public static String generics() throws Exception {
        Type token = new Token<List<Item>>() {}.type;
        ParameterizedType list = (ParameterizedType) token;
        TypeVariable<?> t = Box.class.getTypeParameters()[0];
        ParameterizedType map = (ParameterizedType) Box.class.getField("byName").getGenericType();
        ParameterizedType inner = (ParameterizedType) map.getActualTypeArguments()[1];
        ParameterizedType names = (ParameterizedType) Box.class.getMethod("names").getGenericReturnType();
        Method max = Box.class.getMethod("max", List.class, Number[].class);
        Type[] params = max.getGenericParameterTypes();
        ParameterizedType items = (ParameterizedType) params[0];
        return (list.getRawType() == List.class) + "," + (list.getActualTypeArguments()[0] == Item.class)
            + "," + t.getName() + "," + (t.getBounds()[0] == Number.class)
            + "," + (map.getRawType() == Map.class) + "," + (map.getActualTypeArguments()[0] == String.class)
            + "," + (inner.getActualTypeArguments()[0] == Item.class)
            + "," + (names.getActualTypeArguments()[0] == String.class)
            + "," + max.getTypeParameters()[0].getName() + "," + items.getActualTypeArguments()[0].getTypeName()
            + "," + params[1].getTypeName() + "," + max.getGenericReturnType().getTypeName();
    }

    // ---- annotations ------------------------------------------------------------------------------

    public enum Kind { A, B }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Tag {
        String value();
        int weight() default 3;
        Kind kind() default Kind.A;
        Class<?> type() default Object.class;
        String[] tags() default {};
    }

    @Inherited
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Marked {}

    /** Kept only in the class file, so reflection must not see it. */
    @Retention(RetentionPolicy.CLASS)
    public @interface Invisible {}

    @Tag("cls") @Marked @Invisible
    public static class Annotated {
        @Tag(value = "f", weight = 7, type = Item.class) public int f;

        @Tag(value = "m", kind = Kind.B, tags = {"x", "y"})
        @Deprecated
        public void m(@Tag("p") String p, String q) {}
    }

    public static class AnnotatedChild extends Annotated {}

    public static String annotations() throws Exception {
        Tag cls = Annotated.class.getAnnotation(Tag.class);
        Field f = Annotated.class.getField("f");
        Tag onField = f.getAnnotation(Tag.class);
        Method m = Annotated.class.getMethod("m", String.class, String.class);
        Tag onMethod = m.getAnnotation(Tag.class);
        Annotation[][] params = m.getParameterAnnotations();
        List<String> out = new ArrayList<>();
        out.add(cls.value() + cls.weight() + cls.kind() + (cls.type() == Object.class) + cls.tags().length);
        out.add(onField.value() + onField.weight() + (onField.type() == Item.class));
        out.add(onMethod.value() + onMethod.kind() + Arrays.toString(onMethod.tags()));
        out.add(params.length + ":" + params[0].length + ":" + ((Tag) params[0][0]).value() + ":" + params[1].length);
        out.add(String.valueOf(m.isAnnotationPresent(Deprecated.class)));
        out.add(String.valueOf(Annotated.class.isAnnotationPresent(Invisible.class)));
        out.add(String.valueOf(Annotated.class.getAnnotations().length));
        out.add(String.valueOf(AnnotatedChild.class.isAnnotationPresent(Marked.class)));
        out.add(String.valueOf(AnnotatedChild.class.isAnnotationPresent(Tag.class)));
        out.add(String.valueOf(cls.annotationType() == Tag.class));
        out.add(String.valueOf(Tag.class.getMethod("weight").getDefaultValue()));
        return String.join(",", out);
    }
}
