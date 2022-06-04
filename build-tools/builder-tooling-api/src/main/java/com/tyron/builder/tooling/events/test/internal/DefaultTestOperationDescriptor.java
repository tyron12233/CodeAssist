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

package com.tyron.builder.tooling.events.test.internal;

import com.tyron.builder.tooling.events.OperationDescriptor;
import com.tyron.builder.tooling.events.internal.DefaultOperationDescriptor;
import com.tyron.builder.tooling.events.internal.OperationDescriptorWrapper;
import com.tyron.builder.tooling.events.test.TestOperationDescriptor;
import com.tyron.builder.tooling.internal.protocol.events.InternalOperationDescriptor;
import com.tyron.builder.tooling.internal.protocol.events.InternalTestDescriptor;

/**
 * Implementation of the {@code TestOperationDescriptor} interface.
 */
public class DefaultTestOperationDescriptor extends DefaultOperationDescriptor implements TestOperationDescriptor, OperationDescriptorWrapper {
    private final InternalTestDescriptor internalTestDescriptor;

    public DefaultTestOperationDescriptor(InternalTestDescriptor internalTestDescriptor, OperationDescriptor parent) {
        super(internalTestDescriptor, parent);
        this.internalTestDescriptor = internalTestDescriptor;
    }

    @Override
    public InternalOperationDescriptor getInternalOperationDescriptor() {
        return internalTestDescriptor;
    }
}
