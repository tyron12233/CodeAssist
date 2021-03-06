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

import com.tyron.builder.api.artifacts.ExternalModuleDependencyBundle;
import com.tyron.builder.api.artifacts.MinimalExternalModuleDependency;
import com.tyron.builder.api.internal.artifacts.DefaultModuleIdentifier;
import com.tyron.builder.api.internal.artifacts.ImmutableVersionConstraint;
import com.tyron.builder.api.internal.artifacts.dependencies.DefaultMinimalDependency;
import com.tyron.builder.api.internal.artifacts.dependencies.DefaultMutableVersionConstraint;
import com.tyron.builder.api.provider.Property;
import com.tyron.builder.api.provider.ValueSource;
import com.tyron.builder.api.provider.ValueSourceParameters;

import java.util.ArrayList;
import java.util.stream.Collectors;

public abstract class DependencyBundleValueSource implements ValueSource<ExternalModuleDependencyBundle, DependencyBundleValueSource.Params> {

    interface Params extends ValueSourceParameters {
        Property<String> getBundleName();

        Property<DefaultVersionCatalog> getConfig();
    }

    @Override
    public ExternalModuleDependencyBundle obtain() {
        String bundle = getParameters().getBundleName().get();
        DefaultVersionCatalog config = getParameters().getConfig().get();
        BundleModel bundleModel = config.getBundle(bundle);
        return bundleModel.getComponents().stream()
            .map(config::getDependencyData)
            .map(this::createDependency)
            .collect(Collectors.toCollection(DefaultBundle::new));

    }

    private DefaultMinimalDependency createDependency(DependencyModel data) {
        ImmutableVersionConstraint version = data.getVersion();
        return new DefaultMinimalDependency(
            DefaultModuleIdentifier.newId(data.getGroup(), data.getName()), new DefaultMutableVersionConstraint(version)
        );
    }

    private static class DefaultBundle extends ArrayList<MinimalExternalModuleDependency> implements ExternalModuleDependencyBundle {
    }

}
