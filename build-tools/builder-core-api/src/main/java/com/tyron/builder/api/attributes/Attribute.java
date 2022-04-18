package com.tyron.builder.api.attributes;

import com.tyron.builder.api.Named;

import java.util.Locale;

/**
 * An attribute is a named entity with a type. It is used in conjunction with a {@link AttributeContainer}
 * to provide a type safe container for attributes. This class isn't intended to store the value of an
 * attribute, but only represent the identity of the attribute. It means that an attribute must be immutable
 * and can potentially be pooled. Attributes can be created using the {@link #of(String, Class) factory method}.
 *
 * @param <T> the type of the named attribute
 *
 * @since 3.3
 */
public class Attribute<T> implements Named {
    private final String name;
    private final Class<T> type;
    private final int hashCode;

    /**
     * Creates a new attribute of the given name with the given type. There's no guarantee that subsequent
     * calls to this method with the same attributes would either return the same instance or different instances
     * of {@link Attribute}, so consumers are required to compare the attributes with the {@link #equals(Object)}
     * method.
     * @param name the name of the attribute
     * @param type the class of the attribute
     * @param <T> the type of the attribute
     * @return an attribute with the given name and type
     */
    public static <T> Attribute<T> of(String name, Class<T> type) {
        return new Attribute<T>(name, type);
    }

    /**
     * Creates a new attribute of the given type, inferring the name of the attribute from the simple type name.
     * This method is useful when there's supposedly only one attribute of a specific type in a container, so there's
     * no need to distinguish by name (but the returned type doesn't enforce it_. There's no guarantee that subsequent
     * calls to this method with the same attributes would either return the same instance or different instances
     * of {@link Attribute}, so consumers are required to compare the attributes with the {@link #equals(Object)}
     * method.
     * @param type the class of the attribute
     * @param <T> the type of the attribute
     * @return an attribute with the given name and type
     */
    public static <T> Attribute<T> of(Class<T> type) {
        return of(type.getCanonicalName().toLowerCase(Locale.ROOT), type);
    }

    private Attribute(String name, Class<T> type) {
        this.name = name;
        this.type = type;
        int hashCode = name.hashCode();
        hashCode = 31 * hashCode + type.hashCode();
        this.hashCode = hashCode;
    }

    /**
     * Returns the name of the attribute.
     * @return the name of the attribute.
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * Returns the type of this attribute.
     * @return the type of this attribute.
     */
    public Class<T> getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        Attribute<?> attribute = (Attribute<?>) o;

        if (!name.equals(attribute.name)) {
            return false;
        }
        return type.equals(attribute.type);

    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return name;
    }


}