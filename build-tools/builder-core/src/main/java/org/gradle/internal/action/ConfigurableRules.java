package org.gradle.internal.action;

import java.util.List;

public interface ConfigurableRules<DETAILS> {
    List<ConfigurableRule<DETAILS>> getConfigurableRules();
    boolean isCacheable();
}
