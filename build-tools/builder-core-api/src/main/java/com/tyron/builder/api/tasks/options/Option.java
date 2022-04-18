package com.tyron.builder.api.tasks.options;


import com.tyron.builder.api.Task;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>Marks a property of a {@link Task} as being configurable from the command-line.</p>
 *
 * <p>This annotation should be attached to a field or a setter method. When attached to a field, {@link #option()}
 * will use the name of the field by default. When attached to a method, {@link #option()} must be specified.</p>
 *
 * <p>An option may have one of the following types:</p>
 * <ul>
 * <li>{@code boolean}</li>
 * <li>{@code Boolean}</li>
 * <li>{@code Property<Boolean>}</li>
 * <li>an {@code enum} type</li>
 * <li>{@code Property<T>} of an enum type</li>
 * <li>{@code String}</li>
 * <li>{@code Property<String>}</li>
 * <li>{@code List<T>} of an {@code enum} type</li>
 * <li>{@code List<String>}</li>
 * </ul>
 *
 * <p>
 * Note: Multiple {@code @Option}s with the same names are disallowed on different methods/fields.
 * Methods with the same signature (i.e. the same name, return type, and parameter types) are allowed to use
 * equal or unequal option names.
 * </p>
 * <p>
 * When the option names are equal, the description and method linked to the option will be the one in the
 * base class (if present), followed by super-classes, and finally interfaces, in an unspecified order.
 * </p>
 * <p>
 * When the option names are unequal, the order described above is used when setting the option's value.
 * If the base class has an option with the name "foo" and an interface has an option with the name "bar",
 * the option "foo" will have precedence over the option "bar" and setting both will result in the value of "foo".
 * </p>
 * <p>
 * <strong>
 *     Depending on this behavior is discouraged. It is only in place to allow legacy migration to interface options.
 * </strong>
 * </p>
 *
 * @since 4.6
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
@Inherited
public @interface Option {

    /**
     * The option to map to this property. Required when annotating a method. May be omitted when annotating a field
     * in which case the field's name will be used.
     *
     * @return The option.
     */
    String option() default "";

    /**
     * The description of this option.
     *
     * @return The description.
     */
    String description();
}

