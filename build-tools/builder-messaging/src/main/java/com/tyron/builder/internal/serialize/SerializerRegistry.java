package com.tyron.builder.internal.serialize;

public interface SerializerRegistry {
    /**
     * Use the given serializer for objects of the given type.
     */
    <T> void register(Class<T> implementationType, Serializer<T> serializer);

    /**
     * Use Java serialization for the specified type and all subtypes. Should be avoided, but useful when migrating to using serializers or when dealing with
     * arbitrary user types.
     */
    <T> void useJavaSerialization(Class<T> implementationType);

    /**
     * Returns true when this registry can serialize objects of the given type.
     */
    boolean canSerialize(Class<?> baseType);

    /**
     * Creates a serializer that uses the current registrations to serialize objects of type T.
     */
    <T> Serializer<T> build(Class<T> baseType);
}