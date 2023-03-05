package org.gradle.api;

/**
 * Types can implement this interface and use the embedded {@link Namer} implementation, to satisfy API that calls for a namer.
 */
public interface Named {

    /**
     * The object's name.
     * <p>
     * Must be constant for the life of the object.
     *
     * @return The name. Never null.
     */
    String getName();

    // -- Internal note --
    // It would be better to only require getName() to return Object and just call toString() on it, but
    // if you have a groovy class with a “String name” property the generated getName() method will not
    // satisfy the Named interface. This seems to be a bug in the Groovy compiler - LD.

    /**
     * An implementation of the namer interface for objects implementing the named interface.
     */
    class Namer implements org.gradle.api.Namer<Named> {

        public static final org.gradle.api.Namer<Named> INSTANCE = new Namer();

        @Override
        public String determineName(Named object) {
            return object.getName();
        }

        @SuppressWarnings("unchecked")
        public static <T> org.gradle.api.Namer<? super T> forType(Class<? extends T> type) {
            if (Named.class.isAssignableFrom(type)) {
                return (org.gradle.api.Namer<T>) INSTANCE;
            } else {
                throw new IllegalArgumentException(String.format("The '%s' cannot be used with FactoryNamedDomainObjectContainer without specifying a Namer as it does not implement the Named interface.", type));
            }
        }
    }
}