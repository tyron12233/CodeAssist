package com.tyron.builder.api.tasks;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Named;
import com.tyron.builder.api.provider.Provider;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>Marks a property as specifying a nested bean, whose properties should be checked for annotations.</p>
 *
 * <p>This annotation should be attached to the getter method in Java or the property in Groovy.
 * Annotations on setters or just the field in Java are ignored.</p>
 *
 * <p>The implementation of the nested bean is tracked as an input, too.
 * This allows tracking behavior such as {@link Action}s as task inputs.</p>
 *
 * <p>This annotations supports {@link Provider} values by treating the result of {@link Provider#get()} as a nested bean.</p>
 *
 * <p>This annotation supports {@link Iterable} values by treating each element as a separate nested bean.
 * As a property name, the index of the element in the iterable prefixed by {@code $} is used, e.g. {@code $0}.
 * If the element implements {@link .Named}, then the property name is composed of {@link Named#getName()} and the index, e.g. {@code name$1}.
 * The ordering of the elements in the iterable is crucial for reliable up-to-date checks and caching.</p>
 *
 * <p>This annotation supports ${@link java.util.Map} values by treating each value of the map as a separate nested bean.
 * The keys of the map are used as property names.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface Nested {
}