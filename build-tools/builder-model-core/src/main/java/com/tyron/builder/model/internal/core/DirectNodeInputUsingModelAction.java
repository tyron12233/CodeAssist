package com.tyron.builder.model.internal.core;

import com.tyron.builder.internal.BiAction;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.TriAction;
import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;

import java.util.List;

public class DirectNodeInputUsingModelAction<T> extends AbstractModelActionWithView<T> {
    private final TriAction<? super MutableModelNode, ? super T, ? super List<ModelView<?>>> action;

    public DirectNodeInputUsingModelAction(ModelReference<T> subject, ModelRuleDescriptor descriptor, List<? extends ModelReference<?>> inputs,
                                           TriAction<? super MutableModelNode, ? super T, ? super List<ModelView<?>>> action) {
        super(subject, descriptor, inputs);
        this.action = action;
    }

    public static <T> DirectNodeInputUsingModelAction<T> of(ModelReference<T> modelReference, ModelRuleDescriptor descriptor, List<? extends ModelReference<?>> inputs,
                                                      TriAction<? super MutableModelNode, ? super T, ? super List<ModelView<?>>> action) {
        return new DirectNodeInputUsingModelAction<T>(modelReference, descriptor, inputs, action);
    }

    public static <T> ModelAction of(ModelReference<T> reference, ModelRuleDescriptor descriptor, List<? extends ModelReference<?>> input, final BiAction<? super MutableModelNode, ? super List<ModelView<?>>> action) {
        return new AbstractModelAction<T>(reference, descriptor, input) {
            @Override
            public void execute(MutableModelNode modelNode, List<ModelView<?>> inputs) {
                action.execute(modelNode, inputs);
            }
        };
    }

    public static <T, I> ModelAction of(ModelReference<T> reference, ModelRuleDescriptor descriptor, ModelReference<I> input, final BiAction<? super MutableModelNode, ? super I> action) {
        return new AbstractModelAction<T>(reference, descriptor, input) {
            @Override
            public void execute(MutableModelNode modelNode, List<ModelView<?>> inputs) {
                action.execute(modelNode, Cast.<I>uncheckedCast(inputs.get(0).getInstance()));
            }
        };
    }

    public static <T, I, J> ModelAction of(ModelReference<T> reference, ModelRuleDescriptor descriptor, ModelReference<I> input1, ModelReference<J> input2, final TriAction<? super MutableModelNode, ? super I, ? super J> action) {
        return new AbstractModelAction<T>(reference, descriptor, input1, input2) {
            @Override
            public void execute(MutableModelNode modelNode, List<ModelView<?>> inputs) {
                action.execute(modelNode,
                    Cast.<I>uncheckedCast(inputs.get(0).getInstance()),
                    Cast.<J>uncheckedCast(inputs.get(1).getInstance())
                );
            }
        };
    }

    @Override
    public void execute(MutableModelNode modelNode, T view, List<ModelView<?>> inputs) {
        action.execute(modelNode, view, inputs);
    }
}
