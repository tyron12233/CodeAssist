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
package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.artifacts.ModuleIdentifier;
import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.internal.service.scopes.Scope.Global;
import com.tyron.builder.internal.service.scopes.ServiceScope;

@ServiceScope(Global.class)
public interface ImmutableModuleIdentifierFactory {
    ModuleIdentifier module(String group, String name);
    ModuleVersionIdentifier moduleWithVersion(String group, String name, String version);
    ModuleVersionIdentifier moduleWithVersion(Module module);
    ModuleVersionIdentifier moduleWithVersion(ModuleIdentifier targetModuleId, String version);
}
