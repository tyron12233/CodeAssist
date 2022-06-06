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

package com.tyron.builder.api.internal.artifacts.configurations;

import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.projectresult.ResolvedProjectConfiguration;

import com.tyron.builder.api.internal.project.ProjectStateUnk;
import com.tyron.builder.internal.service.scopes.Scope;
import com.tyron.builder.internal.service.scopes.ServiceScope;

import javax.annotation.Nullable;

@ServiceScope(Scope.Global.class)
public class MarkConfigurationObservedListener implements ProjectDependencyObservedListener {
    @Override
    public void dependencyObserved(@Nullable ProjectStateUnk consumingProject, ProjectStateUnk targetProject, ConfigurationInternal.InternalState requestedState, ResolvedProjectConfiguration target) {
        ConfigurationInternal targetConfig = (ConfigurationInternal) targetProject.getMutableModel().getConfigurations().findByName(target.getTargetConfiguration());
        if (targetConfig != null) {
            // Can be null when dependency metadata for target project has been loaded from cache
            targetConfig.markAsObserved(requestedState);
        }
    }
}
