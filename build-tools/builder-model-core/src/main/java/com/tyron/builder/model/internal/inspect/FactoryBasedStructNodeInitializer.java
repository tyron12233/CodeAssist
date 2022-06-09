/*
 * Copyright 2016 the original author or authors.
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

import com.tyron.builder.internal.Cast;
import com.tyron.builder.model.internal.core.MutableModelNode;
import com.tyron.builder.model.internal.manage.binding.StructBindings;
import com.tyron.builder.model.internal.type.ModelType;
import com.tyron.builder.model.internal.typeregistration.InstanceFactory;

public class FactoryBasedStructNodeInitializer<T, S extends T> extends StructNodeInitializer<S> {
    private final InstanceFactory.ImplementationInfo implementationInfo;

    public FactoryBasedStructNodeInitializer(StructBindings<S> bindings, InstanceFactory.ImplementationInfo implementationInfo) {
        super(bindings);
        this.implementationInfo = implementationInfo;
    }

    @Override
    protected void initializePrivateData(MutableModelNode modelNode) {
        ModelType<T> delegateType = Cast.uncheckedCast(implementationInfo.getDelegateType());
        T instance = Cast.uncheckedCast(implementationInfo.create(modelNode));
        modelNode.setPrivateData(delegateType, instance);
    }
}
