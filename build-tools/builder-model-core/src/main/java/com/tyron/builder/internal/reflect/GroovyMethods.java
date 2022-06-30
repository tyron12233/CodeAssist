package com.tyron.builder.internal.reflect;

import com.google.common.base.Equivalence;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import groovy.lang.GroovyObject;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static com.tyron.builder.internal.reflect.Methods.SIGNATURE_EQUIVALENCE;

public class GroovyMethods {

    private static final Set<Equivalence.Wrapper<Method>> OBJECT_METHODS = ImmutableSet.copyOf(
        Iterables.transform(
            Iterables.concat(
                Arrays.asList(Object.class.getMethods()),
                Arrays.asList(GroovyObject.class.getMethods())
            ), input -> SIGNATURE_EQUIVALENCE.wrap(input))
    );

    /**
     * Is defined by Object or GroovyObject?
     */
    public static boolean isObjectMethod(Method method) {
        return OBJECT_METHODS.contains(SIGNATURE_EQUIVALENCE.wrap(method));
    }
}
