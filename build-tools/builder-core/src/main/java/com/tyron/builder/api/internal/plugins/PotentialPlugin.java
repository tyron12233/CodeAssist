package com.tyron.builder.api.internal.plugins;

/**
 * A plugin that could be applied.
 *
 * This may represent an invalid plugin.
 *
 * At the moment it does not encompass plugins that aren't implemented as classes, but it is likely to in the future.
 */
public interface PotentialPlugin<T> {

    enum Type {
        UNKNOWN,
        IMPERATIVE_CLASS,
        PURE_RULE_SOURCE_CLASS,
        HYBRID_IMPERATIVE_AND_RULES_CLASS
    }

    Class<? extends T> asClass();

    boolean isImperative();

    boolean isHasRules();

    Type getType();

}
