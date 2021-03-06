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
import com.tyron.builder.internal.resolve.ModuleVersionNotFoundException;
import com.tyron.builder.internal.resolve.ModuleVersionResolveException;

import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;
import com.tyron.builder.api.internal.artifacts.DefaultModuleVersionIdentifier;

public class DefaultBuildableComponentResolveResult extends DefaultResourceAwareResolveResult implements BuildableComponentResolveResult {
    private ComponentResolveMetadata metadata;
    private ModuleVersionResolveException failure;

    public DefaultBuildableComponentResolveResult() {
    }

    @Override
    public DefaultBuildableComponentResolveResult failed(ModuleVersionResolveException failure) {
        metadata = null;
        this.failure = failure;
        return this;
    }

    @Override
    public void notFound(ModuleComponentIdentifier versionIdentifier) {
        failed(new ModuleVersionNotFoundException(DefaultModuleVersionIdentifier.newId(versionIdentifier), getAttempted()));
    }

    @Override
    public void resolved(ComponentResolveMetadata metaData) {
        this.metadata = metaData;
    }

    @Override
    public void setMetadata(ComponentResolveMetadata metadata) {
        assertResolved();
        this.metadata = metadata;
    }

    @Override
    public ComponentIdentifier getId() {
        assertResolved();
        return metadata.getId();
    }

    @Override
    public ModuleVersionIdentifier getModuleVersionId() throws ModuleVersionResolveException {
        assertResolved();
        return metadata.getModuleVersionId();
    }

    @Override
    public ComponentResolveMetadata getMetadata() throws ModuleVersionResolveException {
        assertResolved();
        return metadata;
    }

    @Override
    public ModuleVersionResolveException getFailure() {
        assertHasResult();
        return failure;
    }

    private void assertResolved() {
        assertHasResult();
        if (failure != null) {
            throw failure;
        }
    }

    private void assertHasResult() {
        if (!hasResult()) {
            throw new IllegalStateException("No result has been specified.");
        }
    }

    @Override
    public boolean hasResult() {
        return failure != null || metadata != null;
    }

    public void applyTo(BuildableComponentIdResolveResult idResolve) {
        super.applyTo(idResolve);
        if (failure != null) {
            idResolve.failed(failure);
        }
        if (metadata != null) {
            idResolve.resolved(metadata);
        }
    }

}
