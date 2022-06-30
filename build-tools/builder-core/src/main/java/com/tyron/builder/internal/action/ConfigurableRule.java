package com.tyron.builder.internal.action;

import com.tyron.builder.api.Action;
import com.tyron.builder.internal.isolation.Isolatable;

public interface ConfigurableRule<DETAILS> {
    Class<? extends Action<DETAILS>> getRuleClass();
    Isolatable<Object[]> getRuleParams();
    boolean isCacheable();
}
