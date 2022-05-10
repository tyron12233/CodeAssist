package com.tyron.builder.internal.rules;

import java.util.List;

/**
 * An action representing a rule, taking declared inputs and performing an action on a subject.
 *
 * @param <T> The subject type
 */
public interface RuleAction<T> {
    List<Class<?>> getInputTypes();
    void execute(T subject, List<?> inputs);
}
