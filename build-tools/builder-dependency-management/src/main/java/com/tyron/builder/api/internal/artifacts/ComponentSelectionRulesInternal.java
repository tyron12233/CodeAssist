package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.artifacts.ComponentSelection;
import com.tyron.builder.api.artifacts.ComponentSelectionRules;
import com.tyron.builder.internal.rules.RuleAction;
import com.tyron.builder.internal.rules.SpecRuleAction;

import java.util.Collection;

public interface ComponentSelectionRulesInternal extends ComponentSelectionRules {
    Collection<SpecRuleAction<? super ComponentSelection>> getRules();
    ComponentSelectionRules addRule(SpecRuleAction<? super ComponentSelection> specRuleAction);
    ComponentSelectionRules addRule(RuleAction<? super ComponentSelection> specRuleAction);
}
