package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.artifacts.ExcludeRule;
import com.tyron.builder.api.artifacts.ExcludeRuleContainer;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class DefaultExcludeRuleContainer implements ExcludeRuleContainer {
    private Set<ExcludeRule> addedRules;

    public DefaultExcludeRuleContainer() {}

    public DefaultExcludeRuleContainer(Set<ExcludeRule> addedRules) {
        this.addedRules = new HashSet<ExcludeRule>(addedRules);
    }

    @Override
    public void add(Map<String, String> args) {
        maybeAdd(args);
    }

    public boolean maybeAdd(Map<String, String> args) {
        if (addedRules == null) {
            addedRules = new HashSet<ExcludeRule>();
        }
        return addedRules.add(ExcludeRuleNotationConverter.parser().parseNotation(args));
    }

    @Override
    public Set<ExcludeRule> getRules() {
        return addedRules == null ? Collections.<ExcludeRule>emptySet() : addedRules;
    }
}
