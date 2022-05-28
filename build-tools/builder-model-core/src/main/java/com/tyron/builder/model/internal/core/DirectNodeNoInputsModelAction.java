package com.tyron.builder.model.internal.core;

import com.tyron.builder.api.Action;
import com.tyron.builder.internal.BiAction;
import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;

import java.util.Collections;
import java.util.List;

public class DirectNodeNoInputsModelAction<T> extends AbstractModelActionWithView<T> {

    private final BiAction<? super MutableModelNode, ? super T> action;

    private DirectNodeNoInputsModelAction(ModelReference<T> subjectReference, ModelRuleDescriptor descriptor, BiAction<? super MutableModelNode, ? super T> action) {
        super(subjectReference, descriptor, Collections.<ModelReference<?>>emptyList());
        this.action = action;
    }

    public static <T> ModelAction of(ModelReference<T> reference, ModelRuleDescriptor descriptor, final Action<? super MutableModelNode> action) {
        return new AbstractModelAction<T>(reference, descriptor, Collections.<ModelReference<?>>emptyList()) {
            @Override
            public void execute(MutableModelNode modelNode, List<ModelView<?>> inputs) {
                action.execute(modelNode);
            }
        };
    }

    public static <T> ModelAction of(ModelReference<T> reference, ModelRuleDescriptor descriptor, BiAction<? super MutableModelNode, ? super T> action) {
        return new DirectNodeNoInputsModelAction<T>(reference, descriptor, action);
    }

    @Override
    public void execute(MutableModelNode modelNode, T view, List<ModelView<?>> inputs) {
        action.execute(modelNode, view);
    }
}
