package com.tyron.builder.internal.rules;

import java.util.function.Predicate;

/**
 * Represents a tuple containing a Spec and a RuleAction
 */
public class SpecRuleAction<T> {
    final RuleAction<? super T> action;
    final Predicate<? super T> spec;

    public SpecRuleAction(RuleAction<? super T> action, Predicate<? super T> spec) {
        this.action = action;
        this.spec = spec;
    }

    public RuleAction<? super T> getAction() {
        return action;
    }

    public Predicate<? super T> getSpec() {
        return spec;
    }
}
