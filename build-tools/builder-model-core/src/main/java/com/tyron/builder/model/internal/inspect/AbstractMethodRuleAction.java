/*
 * Copyright 2015 the original author or authors.
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
import com.tyron.builder.model.internal.core.MutableModelNode;
import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;

import java.util.List;

public abstract class AbstractMethodRuleAction<T> implements MethodRuleAction {
    private final ModelReference<T> subject;
    private final ModelRuleDescriptor descriptor;

    public AbstractMethodRuleAction(ModelReference<T> subject, ModelRuleDescriptor descriptor) {
        this.subject = subject;
        this.descriptor = descriptor;
    }

    @Override
    public ModelReference<T> getSubject() {
        return subject;
    }

    public ModelRuleDescriptor getDescriptor() {
        return descriptor;
    }

    @Override
    public void execute(ModelRuleInvoker<?> invoker, MutableModelNode modelNode, List<ModelView<?>> inputs) {
        ModelView<? extends T> subjectView = modelNode.asMutable(getSubject().getType(), descriptor);
        try {
            execute(invoker, subjectView.getInstance(), inputs);
        } finally {
            subjectView.close();
        }
    }

    protected abstract void execute(ModelRuleInvoker<?> invoker, T subject, List<ModelView<?>> inputs);

}
