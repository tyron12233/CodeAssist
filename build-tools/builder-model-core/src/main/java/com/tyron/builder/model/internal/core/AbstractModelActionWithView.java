package com.tyron.builder.model.internal.core;

import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;
import com.tyron.builder.model.internal.type.ModelType;

import java.util.List;

public abstract class AbstractModelActionWithView<T> extends AbstractModelAction<T> {
    protected AbstractModelActionWithView(ModelReference<T> subject, ModelRuleDescriptor descriptor, List<? extends ModelReference<?>> inputs) {
        super(subject, descriptor, inputs);
    }

    @Override
    final public void execute(MutableModelNode node, List<ModelView<?>> inputs) {
        if (!node.isAtLeast(ModelNode.State.Created)) {
            throw new IllegalStateException("Cannot get view for node " + node.getPath() + " in state " + node.getState());
        }
        ModelType<T> type = getSubject().getType();
        ModelView<? extends T> view = node.asMutable(type, getDescriptor());
        try {
            execute(node, view.getInstance(), inputs);
        } finally {
            view.close();
        }
    }

    protected abstract void execute(MutableModelNode modelNode, T view, List<ModelView<?>> inputs);
}
