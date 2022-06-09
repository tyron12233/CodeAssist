/*
 * Copyright 2017 the original author or authors.
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

package com.tyron.builder.plugin.management.internal.autoapply;

import com.tyron.builder.StartParameter;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.artifacts.ModuleIdentifier;
import com.tyron.builder.api.artifacts.ModuleVersionSelector;
import com.tyron.builder.api.initialization.Settings;
import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.internal.artifacts.DefaultModuleIdentifier;
import com.tyron.builder.api.internal.artifacts.DefaultModuleVersionSelector;
import com.tyron.builder.api.invocation.Gradle;
import com.tyron.builder.plugin.management.internal.DefaultPluginRequest;
import com.tyron.builder.plugin.management.internal.PluginRequestInternal;
import com.tyron.builder.plugin.management.internal.PluginRequests;

/**
 * A hardcoded {@link AutoAppliedPluginRegistry} that only knows about the build-scan plugin for now.
 */
public class DefaultAutoAppliedPluginRegistry implements AutoAppliedPluginRegistry {

//    private static final PluginRequests GRADLE_ENTERPRISE_PLUGIN_REQUEST = PluginRequests.of(createGradleEnterprisePluginRequest());

    private final BuildDefinition buildDefinition;

    public DefaultAutoAppliedPluginRegistry(BuildDefinition buildDefinition) {
        this.buildDefinition = buildDefinition;
    }

    @Override
    public PluginRequests getAutoAppliedPlugins(BuildProject target) {
        return PluginRequests.EMPTY;
    }

    @Override
    public PluginRequests getAutoAppliedPlugins(Settings target) {
        if (((StartParameterInternal) target.getStartParameter()).isUseEmptySettings()) {
            return PluginRequests.EMPTY;
        }

        PluginRequests injectedPluginRequests = buildDefinition.getInjectedPluginRequests();

        if (shouldApplyGradleEnterprisePlugin(target)) {
            throw new UnsupportedOperationException();
//            return injectedPluginRequests.mergeWith(GRADLE_ENTERPRISE_PLUGIN_REQUEST);
        } else {
            return injectedPluginRequests;
        }
    }

    private boolean shouldApplyGradleEnterprisePlugin(Settings settings) {
//        Gradle gradle = settings.getGradle();
//        StartParameter startParameter = gradle.getStartParameter();
//        return startParameter.isBuildScan() && gradle.getParent() == null;
        return false;
    }

    private static PluginRequestInternal createGradleEnterprisePluginRequest() {
//        ModuleIdentifier moduleIdentifier = DefaultModuleIdentifier.newId(AutoAppliedGradleEnterprisePlugin.GROUP, AutoAppliedGradleEnterprisePlugin.NAME);
//        ModuleVersionSelector artifact = DefaultModuleVersionSelector.newSelector(moduleIdentifier, AutoAppliedGradleEnterprisePlugin.VERSION);
//        return new DefaultPluginRequest(AutoAppliedGradleEnterprisePlugin.ID, AutoAppliedGradleEnterprisePlugin.VERSION, true, null, getScriptDisplayName(), artifact);
        throw new UnsupportedOperationException();
    }

    private static String getScriptDisplayName() {
        return String.format("auto-applied by using --%s", "Build scan long option");
    }
}
