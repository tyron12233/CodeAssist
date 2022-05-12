package com.tyron.builder.api.attributes;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.ActionConfiguration;
import com.tyron.builder.internal.HasInternalProtocol;

import java.util.Comparator;

/**
 * <p>A chain of compatibility checks, implemented as action rules. By default
 * the chain is empty and will eventually tell the values are incompatible if no rule expressed
 * an opinion.</p>
 *
 * <p>For a given set of rules, the execution is done <i>in order</i>, and interrupts as soon as a rule
 * expressed an option (through {@link CompatibilityCheckDetails#compatible()} or {@link CompatibilityCheckDetails#incompatible()}).
 * </p>
 *
 * <p>If the end of the rule chain is reached and that no rule expressed an opinion then we apply an equality check by default, and
 * eventually fail if they are not equal.</p>
 *
 * @param <T> the type of the attribute
 */
@HasInternalProtocol
public interface CompatibilityRuleChain<T> {
    /**
     * Adds an ordered check rule to this chain.
     *
     * @param comparator the comparator to use
     */
    void ordered(Comparator<? super T> comparator);

    /**
     * Adds an reverse ordered check rule to this chain.
     *
     * @param comparator the comparator to use
     */
    void reverseOrdered(Comparator<? super T> comparator);

    /**
     * <p>Adds an arbitrary compatibility rule to the chain.</p>
     *
     * <p>A compatibility rule can tell if two values are compatible.
     * Compatibility doesn't mean equality. Typically two different Java platforms can be
     * compatible, without being equal.</p>
     *
     * <p>A rule <i>can</i> express an opinion by calling the @{link {@link CompatibilityCheckDetails#compatible()}}
     * method to tell that two attributes are compatible, or it <i>can</i> call {@link CompatibilityCheckDetails#incompatible()}
     * to say that they are not compatible. It is not mandatory for a rule to express an opinion.</p>
     *
     * @param rule the rule to add to the chain
     * @since 4.0
     */
    void add(Class<? extends AttributeCompatibilityRule<T>> rule);

    /**
     * <p>Adds an arbitrary compatibility rule to the chain, possibly configuring the rule as well.</p>
     *
     * @param rule the rule to add to the chain
     * @param configureAction the action to use to configure the rule
     * @since 4.0
     */
    void add(Class<? extends AttributeCompatibilityRule<T>> rule, Action<? super ActionConfiguration> configureAction);

}
