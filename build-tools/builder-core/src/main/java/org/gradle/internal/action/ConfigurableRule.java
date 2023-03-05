package org.gradle.internal.action;

import org.gradle.api.Action;
import org.gradle.internal.isolation.Isolatable;

public interface ConfigurableRule<DETAILS> {
    Class<? extends Action<DETAILS>> getRuleClass();
    Isolatable<Object[]> getRuleParams();
    boolean isCacheable();
}
