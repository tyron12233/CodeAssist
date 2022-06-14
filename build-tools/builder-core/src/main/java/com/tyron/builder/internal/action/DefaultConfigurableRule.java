package com.tyron.builder.internal.action;

import com.google.common.base.Objects;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.ActionConfiguration;
import com.tyron.builder.api.artifacts.CacheableRule;
import com.tyron.builder.api.internal.DefaultActionConfiguration;
import com.tyron.builder.internal.isolation.Isolatable;
import com.tyron.builder.internal.isolation.IsolatableFactory;
import com.tyron.builder.internal.reflect.JavaPropertyReflectionUtil;
import com.tyron.builder.internal.snapshot.impl.IsolatedArray;

import java.util.Arrays;

public class DefaultConfigurableRule<DETAILS> implements ConfigurableRule<DETAILS> {
    private final static Object[] EMPTY_ARRAY = new Object[0];

    private final Class<? extends Action<DETAILS>> rule;
    private final Isolatable<Object[]> ruleParams;
    private final boolean cacheable;

    private DefaultConfigurableRule(Class<? extends Action<DETAILS>> rule, Isolatable<Object[]> ruleParams) {
        this.rule = rule;
        this.ruleParams = ruleParams;
        this.cacheable = hasCacheableAnnotation(rule);
    }

    private static <DETAILS> boolean hasCacheableAnnotation(Class<? extends Action<DETAILS>> rule) {
        return JavaPropertyReflectionUtil.getAnnotation(rule, CacheableRule.class) != null;
    }

    public static <DETAILS> ConfigurableRule<DETAILS> of(Class<? extends Action<DETAILS>> rule) {
        return new DefaultConfigurableRule<DETAILS>(rule, IsolatedArray.EMPTY);
    }

    public static <DETAILS> ConfigurableRule<DETAILS> of(Class<? extends Action<DETAILS>> rule, Action<? super ActionConfiguration> action, IsolatableFactory isolatableFactory) {
        Object[] params = EMPTY_ARRAY;
        if (action != null) {
            ActionConfiguration configuration = new DefaultActionConfiguration();
            action.execute(configuration);
            params = configuration.getParams();
        }
        return new DefaultConfigurableRule<DETAILS>(rule, isolatableFactory.isolate(params));
    }

    @Override
    public Class<? extends Action<DETAILS>> getRuleClass() {
        return rule;
    }

    @Override
    public Isolatable<Object[]> getRuleParams() {
        return ruleParams;
    }

    @Override
    public boolean isCacheable() {
        return cacheable;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DefaultConfigurableRule<?> that = (DefaultConfigurableRule<?>) o;
        return cacheable == that.cacheable &&
            Objects.equal(rule, that.rule) &&
            Objects.equal(ruleParams, that.ruleParams);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(rule, ruleParams, cacheable);
    }

    @Override
    public String toString() {
        return "DefaultConfigurableRule{" +
            "rule=" + rule +
            ", ruleParams=" + Arrays.toString(ruleParams.isolate()) +
            '}';
    }
}
