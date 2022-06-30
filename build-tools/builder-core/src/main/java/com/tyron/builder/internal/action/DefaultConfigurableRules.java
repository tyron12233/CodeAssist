package com.tyron.builder.internal.action;

import com.google.common.collect.ImmutableList;

import java.util.List;

import static java.util.Collections.singletonList;

public class DefaultConfigurableRules<DETAILS> implements ConfigurableRules<DETAILS> {

    public static <T> ConfigurableRules<T> of(ConfigurableRule<T> unique) {
        return new DefaultConfigurableRules<T>(singletonList(unique));
    }

    private final List<ConfigurableRule<DETAILS>> configurableRules;
    private final boolean cacheable;

    public DefaultConfigurableRules(List<ConfigurableRule<DETAILS>> rules) {
        this.configurableRules = ImmutableList.copyOf(rules);

        cacheable = computeCacheable();
    }

    private boolean computeCacheable() {
        boolean isCacheable = false;
        for (ConfigurableRule<DETAILS> configurableRule : configurableRules) {
            if (configurableRule.isCacheable()) {
                isCacheable = true;
            }
        }
        return isCacheable;
    }

    @Override
    public List<ConfigurableRule<DETAILS>> getConfigurableRules() {
        return configurableRules;
    }

    @Override
    public boolean isCacheable() {
        return cacheable;
    }

    @Override
    public String toString() {
        return configurableRules.toString();
    }
}
