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
package com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.factories;

import com.tyron.builder.api.artifacts.ModuleIdentifier;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeEverything;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeNothing;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.GroupExclude;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.GroupSetExclude;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ModuleExclude;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ModuleIdExclude;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ModuleIdSetExclude;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ModuleSetExclude;
import com.tyron.builder.internal.component.model.IvyArtifactName;

import java.util.Set;

public interface ExcludeFactory {
    ExcludeNothing nothing();

    ExcludeEverything everything();

    GroupExclude group(String group);

    ModuleExclude module(String module);

    ModuleIdExclude moduleId(ModuleIdentifier id);

    ExcludeSpec anyOf(ExcludeSpec one, ExcludeSpec two);

    ExcludeSpec allOf(ExcludeSpec one, ExcludeSpec two);

    ExcludeSpec anyOf(Set<ExcludeSpec> specs);

    ExcludeSpec allOf(Set<ExcludeSpec> specs);

    ExcludeSpec ivyPatternExclude(ModuleIdentifier moduleId, IvyArtifactName artifact, String matcher);

    ModuleIdSetExclude moduleIdSet(Set<ModuleIdentifier> modules);

    GroupSetExclude groupSet(Set<String> groups);

    ModuleSetExclude moduleSet(Set<String> modules);
}
