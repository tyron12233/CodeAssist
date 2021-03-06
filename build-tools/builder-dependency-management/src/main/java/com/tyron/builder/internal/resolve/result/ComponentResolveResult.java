/*
 * Copyright 2012 the original author or authors.
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
package com.tyron.builder.internal.resolve.result;

import com.tyron.builder.internal.component.model.ComponentResolveMetadata;
import com.tyron.builder.internal.resolve.ModuleVersionResolveException;

import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;

import javax.annotation.Nullable;

/**
 * The result of resolving a module version selector to a particular component.
 *
 * <p>Very similar to {@link ComponentIdResolveResult}, could probably merge these.
 */
public interface ComponentResolveResult extends ResolveResult {

    /**
     * Returns the identifier of the component.
     */
    ComponentIdentifier getId();

    /**
     * Returns the module version id of the component.
     *
     * @throws ModuleVersionResolveException If resolution was unsuccessful and the id is unknown.
     */
    ModuleVersionIdentifier getModuleVersionId() throws ModuleVersionResolveException;

    /**
     * Returns the meta-data for the component.
     *
     * @throws ModuleVersionResolveException If resolution was unsuccessful and the descriptor is not available.
     */
    ComponentResolveMetadata getMetadata() throws ModuleVersionResolveException;

    /**
     * Returns the resolve failure, if any.
     */
    @Override
    @Nullable
    ModuleVersionResolveException getFailure();
}
