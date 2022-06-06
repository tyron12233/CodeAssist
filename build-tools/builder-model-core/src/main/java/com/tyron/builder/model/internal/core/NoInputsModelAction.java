package com.tyron.builder.model.internal.core;

import com.tyron.builder.api.Action;
import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;

import java.util.Collections;
import java.util.List;

public class NoInputsModelAction<T> extends AbstractModelActionWithView<T> {
    private final Action<? super T> configAction;

    public NoInputsModelAction(ModelReference<T> subject, ModelRuleDescriptor descriptor, Action<? super T> configAction) {
        super(subject, descriptor, Collections.emptyList());
        this.configAction = configAction;
    }

    public static <T> ModelAction of(ModelReference<T> reference, ModelRuleDescriptor descriptor, Action<? super T> configAction) {
        return new NoInputsModelAction<>(reference, descriptor, configAction);
    }

    @Override
    public void execute(MutableModelNode modelNode, T view, List<ModelView<?>> inputs) {
        configAction.execute(view);
    }
}
