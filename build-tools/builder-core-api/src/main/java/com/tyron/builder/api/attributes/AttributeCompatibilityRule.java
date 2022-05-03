package com.tyron.builder.api.attributes;

import com.tyron.builder.api.Action;

/**
 * A rule that determines whether a given attribute value is compatible some provided attribute value.
 * <p>
 * The provided {@link CompatibilityCheckDetails} will give access to consumer and producer values and allow implementation
 * mark the producer value as compatible or not.
 * <p>
 * Note that the rule will never receive a {@code CompatibilityCheckDetails} that has {@code equal} consumer and producer
 * values as this check is performed before invoking the rule and assumes compatibility in that case.
 *
 * @since 4.0
 * @param <T> The attribute value type.
 * @see CompatibilityCheckDetails
 */
public interface AttributeCompatibilityRule<T> extends Action<CompatibilityCheckDetails<T>> {
}
