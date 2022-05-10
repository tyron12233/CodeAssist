package com.tyron.builder.api;

import com.tyron.builder.internal.Cast;
import com.tyron.builder.util.internal.CollectionUtils;

import groovy.lang.Closure;
import java.util.List;

/**
 * Thrown when a {@link Closure} is given as an {@link Action} implementation, but has the wrong signature.
 */
public class InvalidActionClosureException extends BuildException {

    private final Closure<?> closure;
    private final Object argument;

    public InvalidActionClosureException(Closure<?> closure, Object argument) {
        super(toMessage(closure, argument));
        this.closure = closure;
        this.argument = argument;
    }

    private static String toMessage(Closure<?> closure, Object argument) {
        List<Object> classNames = CollectionUtils
                .collect(Cast.<Class<?>[]>uncheckedNonnullCast(closure.getParameterTypes()), Class::getName);
        return String.format(
                "The closure '%s' is not valid as an action for argument '%s'. It should accept no parameters, or one compatible with type '%s'. It accepts (%s).",
                closure, argument, argument.getClass().getName(), CollectionUtils.join(", ", classNames)
        );
    }

    /**
     * The closure being used as an action.
     *
     * @return The closure being used as an action.
     */
    public Closure<?> getClosure() {
        return closure;
    }

    /**
     * The argument the action was executed with.
     *
     * @return The argument the action was executed with.
     */
    public Object getArgument() {
        return argument;
    }
}
