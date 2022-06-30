package com.tyron.builder.model.internal.core.rule.describe;

/**
 * Describes a method rule.
 * All implementations of this class are expected to implement the equals and hashCode method
 */
public interface ModelRuleDescriptor {
    /**
     * This method is expected to be idempotent.
     *
     * @param appendable where to write the description to.
     */
    void describeTo(Appendable appendable);

    ModelRuleDescriptor append(ModelRuleDescriptor child);

    ModelRuleDescriptor append(String child);

    ModelRuleDescriptor append(String child, Object... args);
}
