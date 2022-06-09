/*
 * Copyright 2019 the original author or authors.
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

package com.tyron.builder.internal.build.event.types;

import com.tyron.builder.internal.operations.OperationIdentifier;
import com.tyron.builder.tooling.internal.protocol.events.InternalTestOutputDescriptor;

import java.io.Serializable;

public class DefaultTestOutputDescriptor implements Serializable, InternalTestOutputDescriptor {

    private final OperationIdentifier id;
    private final OperationIdentifier parentId;

    public DefaultTestOutputDescriptor(OperationIdentifier id, OperationIdentifier parentId) {
        this.id = id;
        this.parentId = parentId;
    }

    @Override
    public OperationIdentifier getId() {
        return id;
    }

    @Override
    public String getName() {
        return "output";
    }

    @Override
    public String getDisplayName() {
        return "output";
    }

    @Override
    public OperationIdentifier getParentId() {
        return parentId;
    }
}
