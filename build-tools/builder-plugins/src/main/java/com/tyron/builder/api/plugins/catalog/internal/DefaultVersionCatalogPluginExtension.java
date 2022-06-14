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
package com.tyron.builder.api.plugins.catalog.internal;

import com.google.common.collect.Interners;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.api.initialization.dsl.VersionCatalogBuilder;
import com.tyron.builder.api.internal.artifacts.DefaultModuleIdentifier;
import com.tyron.builder.api.internal.artifacts.DependencyResolutionServices;
import com.tyron.builder.api.internal.catalog.DefaultVersionCatalog;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.provider.ProviderFactory;

import javax.inject.Inject;
import java.util.function.Supplier;

public class DefaultVersionCatalogPluginExtension implements CatalogExtensionInternal {
    private final DependenciesAwareVersionCatalogBuilder builder;
    private final Provider<DefaultVersionCatalog> model;

    @Inject
    public DefaultVersionCatalogPluginExtension(ObjectFactory objects, ProviderFactory providers, DependencyResolutionServices drs, Configuration dependenciesConfiguration) {
        this.builder = objects.newInstance(DependenciesAwareVersionCatalogBuilder.class,
            "versionCatalog",
            Interners.newStrongInterner(),
            Interners.newStrongInterner(),
            objects,
            providers,
            (Supplier<DependencyResolutionServices>) () -> drs,
            dependenciesConfiguration
        );
        this.model = providers.provider(builder::build);
    }

    @Override
    public void versionCatalog(Action<? super VersionCatalogBuilder> spec) {
        spec.execute(builder);
    }

    @Override
    public void configureExplicitAlias(String alias, String group, String name) {
        builder.configureExplicitAlias(DefaultModuleIdentifier.newId(group, name), alias);
    }

    @Override
    public Provider<DefaultVersionCatalog> getVersionCatalog() {
        return model;
    }

}
