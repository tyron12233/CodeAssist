/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tyron.builder.model.internal.inspect;

import com.tyron.builder.model.internal.core.ModelReference;
import com.tyron.builder.model.internal.core.ModelView;
import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;

import java.util.List;

class MethodBackedModelAction<T> extends AbstractMethodRuleAction<T> {
    private final List<ModelReference<?>> inputs;

    public MethodBackedModelAction(ModelRuleDescriptor descriptor, ModelReference<T> subject, List<ModelReference<?>> inputs) {
        super(subject, descriptor);
        this.inputs = inputs;
    }

    @Override
    public List<? extends ModelReference<?>> getInputs() {
        return inputs;
    }

    @Override
    protected void execute(ModelRuleInvoker<?> invoker, T subject, List<ModelView<?>> inputs) {
        Object[] args = new Object[1 + this.inputs.size()];
        args[0] = subject;
        for (int i = 0; i < this.inputs.size(); ++i) {
            args[i + 1] = inputs.get(i).getInstance();
        }
        invoker.invoke(args);
    }
}
