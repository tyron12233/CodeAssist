package com.tyron.builder.model.internal.core;

import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;

import java.util.List;

public interface ModelAction {

    ModelReference<?> getSubject();

    void execute(MutableModelNode modelNode, List<ModelView<?>> inputs);

    List<? extends ModelReference<?>> getInputs();

    ModelRuleDescriptor getDescriptor();

}