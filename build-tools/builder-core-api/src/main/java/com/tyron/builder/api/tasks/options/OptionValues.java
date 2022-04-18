package com.tyron.builder.api.tasks.options;

import com.tyron.builder.api.Task;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>Marks a method on a {@link Task} as providing the possible values for a {@code String}
 * or {@code List<String>} {@link Option}. At most one option values method may be provided for a
 * particular option.</p>
 *
 * <p>This annotation should be attached to a getter method that returns a {@link java.util.Collection} of
 * possible values. The entries in the collection may be of any type. If necessary, they are transformed
 * into {@link String Strings} by calling {@code toString()}.</p>
 *
 * @since 4.6
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Inherited
public @interface OptionValues {

    /**
     * The names of the options for which the method provides the possible values.
     * @return the option names
     */
    String[] value();
}