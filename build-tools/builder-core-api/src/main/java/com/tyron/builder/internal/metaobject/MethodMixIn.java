package com.tyron.builder.internal.metaobject;

/**
 * A decorated domain object type may optionally implement this interface to dynamically expose methods in addition to those declared statically on the type.
 *
 * Note that when a type implements this interface, dynamic Groovy dispatch will not be used to discover opaque methods. That is, methods such as methodMissing() will be ignored.
 */
public interface MethodMixIn {
    MethodAccess getAdditionalMethods();
}
