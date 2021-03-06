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

package com.tyron.builder.internal.component.external.ivypublish;

import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;
import com.tyron.builder.internal.Pair;
import com.tyron.builder.internal.component.external.descriptor.Configuration;
import com.tyron.builder.internal.component.model.ExcludeMetadata;
import com.tyron.builder.internal.component.model.LocalOriginDependencyMetadata;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Represents any module (Ivy or Maven) to be published via the legacy publishing mechanism.
 */
public interface IvyModulePublishMetadata {
    String IVY_MAVEN_NAMESPACE = "http://ant.apache.org/ivy/maven";
    String IVY_MAVEN_NAMESPACE_PREFIX = "m";
    String IVY_EXTRA_NAMESPACE = "http://ant.apache.org/ivy/extra";

    String getStatus();

    ModuleComponentIdentifier getComponentId();

    Map<String, Configuration> getConfigurations();

    Collection<IvyModuleArtifactPublishMetadata> getArtifacts();

    Collection<LocalOriginDependencyMetadata> getDependencies();

    List<Pair<ExcludeMetadata, String>> getExcludes();
}
