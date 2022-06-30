package com.tyron.builder.model.internal.core;

import com.google.common.base.Preconditions;
import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public abstract class AbstractModelAction<T> implements ModelAction {
    public static final List<ModelReference<?>> EMPTY_MODEL_REF_LIST = Collections.emptyList();

    protected final ModelReference<T> subject;
    protected final ModelRuleDescriptor descriptor;
    protected final List<? extends ModelReference<?>> inputs;

    protected AbstractModelAction(ModelReference<T> subject, ModelRuleDescriptor descriptor, ModelReference<?>... inputs) {
        this(subject, descriptor, inputs == null ? EMPTY_MODEL_REF_LIST : Arrays.asList(inputs));
    }

    protected AbstractModelAction(ModelReference<T> subject, ModelRuleDescriptor descriptor, List<? extends ModelReference<?>> inputs) {
        this.subject = Preconditions.checkNotNull(subject, "subject");
        this.descriptor = Preconditions.checkNotNull(descriptor, "descriptor");
        Preconditions.checkNotNull(inputs, "inputs");
        this.inputs = inputs.isEmpty() ? EMPTY_MODEL_REF_LIST : inputs;
    }

    @Override
    final public ModelReference<T> getSubject() {
        return subject;
    }

    @Override
    final public ModelRuleDescriptor getDescriptor() {
        return descriptor;
    }

    @Override
    final public List<? extends ModelReference<?>> getInputs() {
        return inputs;
    }
}
