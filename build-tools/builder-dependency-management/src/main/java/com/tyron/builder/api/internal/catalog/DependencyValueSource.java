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

import com.tyron.builder.api.artifacts.MinimalExternalModuleDependency;
import com.tyron.builder.api.internal.artifacts.DefaultModuleIdentifier;
import com.tyron.builder.api.internal.artifacts.ImmutableVersionConstraint;
import com.tyron.builder.api.internal.artifacts.dependencies.DefaultMinimalDependency;
import com.tyron.builder.api.internal.artifacts.dependencies.DefaultMutableVersionConstraint;
import com.tyron.builder.api.provider.Property;
import com.tyron.builder.api.provider.ValueSource;
import com.tyron.builder.api.provider.ValueSourceParameters;

public abstract class DependencyValueSource implements ValueSource<MinimalExternalModuleDependency, DependencyValueSource.Params> {

    interface Params extends ValueSourceParameters {
        Property<DependencyModel> getDependencyData();
    }

    @Override
    public MinimalExternalModuleDependency obtain() {
        DependencyModel data = getParameters().getDependencyData().get();
        ImmutableVersionConstraint version = data.getVersion();
        return new DefaultMinimalDependency(
            DefaultModuleIdentifier.newId(data.getGroup(), data.getName()), new DefaultMutableVersionConstraint(version)
        );
    }
}
