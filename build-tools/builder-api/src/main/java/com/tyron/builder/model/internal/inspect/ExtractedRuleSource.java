package com.tyron.builder.model.internal.inspect;

import com.tyron.builder.internal.Factory;
import com.tyron.builder.model.internal.core.MutableModelNode;
import com.tyron.builder.model.internal.registry.ModelRegistry;

import java.util.List;

public interface ExtractedRuleSource<T> {
    /**
     * Applies the rules of this rule source to the given element.
     */
    void apply(ModelRegistry modelRegistry, MutableModelNode target);

    /**
     * Returns the set of plugins required by the rules of this rule source.
     */
    List<? extends Class<?>> getRequiredPlugins();

    /**
     * Asserts that no additional plugins are required by the rules of this rule source.
     */
    void assertNoPlugins() throws UnsupportedOperationException;

    /**
     * Creates a factory for creating views of this rule source, for invoking rules or binding references. Should be used only before the rule source has been applied.
     */
    Factory<? extends T> getFactory();
}