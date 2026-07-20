package dev.ide.jvm.fixtures;

import java.lang.reflect.Constructor;

/** Reflective instantiation of an interpreted class by name, the pattern CoordinatorLayout uses to create a
 *  behavior from an {@code app:layout_behavior} string: load the class, downcast to the base, find the
 *  constructor, and instantiate. All the types are interpreted, so the VM must service the reflection. */
public final class ReflectInstantiate {
    private ReflectInstantiate() {}

    public abstract static class Animal {
        public abstract int legs();
    }

    public static class Dog extends Animal {
        private final int n;
        public Dog(int n) { this.n = n; }
        @Override public int legs() { return n; }
    }

    /** Load [name] via [cl], downcast to Animal, construct with one int, and call the overridden method. */
    public static int reflect(ClassLoader cl, String name, int legs) throws Exception {
        Class<? extends Animal> c = cl.loadClass(name).asSubclass(Animal.class);
        Constructor<? extends Animal> ctor = c.getConstructor(int.class);
        Animal a = ctor.newInstance(legs);
        return a.legs();
    }

    /** The Class-literal identity a reflectively-loaded interpreted class must match. */
    public static boolean sameClass(ClassLoader cl, String name) throws Exception {
        return cl.loadClass(name) == Dog.class;
    }
}
