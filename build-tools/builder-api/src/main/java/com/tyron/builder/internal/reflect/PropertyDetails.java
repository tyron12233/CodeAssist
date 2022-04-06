package com.tyron.builder.internal.reflect;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

public interface PropertyDetails {

    String getName();

    /**
     * The getter methods for a particular type.
     *
     * This list will only ever contain more than one method when there are getter methods with <b>different</b> return types.
     * If a getter is declared multiple times by this type (through inheritance) with identical return types, only one method object will be present for the type.
     *
     * As an equivalent getter can be declared multiple times (e.g in a super class, overridden by the target type).
     * The actual method instance used is significant.
     * The method instance is for the “nearest” declaration, where classes take precedence over interfaces.
     * Interface precedence is breadth-first, declaration.
     *
     * The order of the methods follows the same precedence.
     * That is, the “nearer” declarations are earlier in the list.
     */
    Collection<Method> getGetters();

    /**
     * Similar to {@link #getGetters()}, but varies based on the param type instead of return type.
     *
     * Has identical ordering semantics.
     */
    Collection<Method> getSetters();

    /**
     * Returns the backing field for this property, if a Groovy implemented property. In this case, the field instead of the getter carries the property annotations.
     */
    @Nullable
    Field getBackingField();
}