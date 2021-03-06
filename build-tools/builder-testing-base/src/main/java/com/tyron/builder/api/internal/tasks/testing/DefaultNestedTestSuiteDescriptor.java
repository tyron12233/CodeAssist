/*
 * Copyright 2020 the original author or authors.
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
package com.tyron.builder.api.internal.tasks.testing;

import com.tyron.builder.internal.id.CompositeIdGenerator;

public class DefaultNestedTestSuiteDescriptor extends DefaultTestSuiteDescriptor {
    private final CompositeIdGenerator.CompositeId parentId;
    private final String displayName;

    public DefaultNestedTestSuiteDescriptor(Object id, String name, String displayName, CompositeIdGenerator.CompositeId parentId) {
        super(id, name);
        this.displayName = displayName;
        this.parentId = parentId;
    }

    public CompositeIdGenerator.CompositeId getParentId() {
        return parentId;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String getClassName() {
        return super.getClassName();
    }
}
