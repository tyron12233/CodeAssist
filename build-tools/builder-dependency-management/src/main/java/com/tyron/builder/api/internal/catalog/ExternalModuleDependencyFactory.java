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
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.provider.ProviderConvertible;
import com.tyron.builder.plugin.use.PluginDependency;

public interface ExternalModuleDependencyFactory {

    Provider<MinimalExternalModuleDependency> create(String alias);

    interface DependencyNotationSupplier extends ProviderConvertible<MinimalExternalModuleDependency> {
    }

    interface VersionNotationSupplier extends ProviderConvertible<String> {
    }

    interface BundleNotationSupplier extends ProviderConvertible<ExternalModuleDependencyBundle> {
    }

    interface PluginNotationSupplier extends ProviderConvertible<PluginDependency> {
    }
}
