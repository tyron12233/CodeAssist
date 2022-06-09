/*
 * Copyright 2021 the original author or authors.
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
package com.tyron.builder.api.internal.catalog;

import com.tyron.builder.api.internal.artifacts.DefaultProjectDependencyFactory;
import com.tyron.builder.api.internal.artifacts.dependencies.ProjectDependencyInternal;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.ProjectFinder;

public abstract class TypeSafeProjectDependencyFactory {
    private final DefaultProjectDependencyFactory factory;
    private final ProjectFinder finder;

    protected TypeSafeProjectDependencyFactory(DefaultProjectDependencyFactory factory, ProjectFinder finder) {
        this.factory = factory;
        this.finder = finder;
    }

    protected ProjectDependencyInternal create(String path) {
        return (ProjectDependencyInternal) factory.create(finder.getProject(path));
    }

    protected TypeSafeProjectDependencyFactory getFactory() {
        return this;
    }
}

