/*
 * Copyright 2013 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.result;

import com.tyron.builder.internal.resolve.ModuleVersionResolveException;

import com.tyron.builder.api.artifacts.component.ComponentSelector;
import com.tyron.builder.api.artifacts.result.ComponentSelectionReason;
import com.tyron.builder.api.artifacts.result.ResolvedVariantResult;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.graph.ResolvedGraphDependency;

/**
 * The deserialized representation of an edge in the resolution result.
 * This is produced by first serializing an `EdgeState` and later deserializing when required.
 */
public class DetachedResolvedGraphDependency implements ResolvedGraphDependency {

    private final ComponentSelector requested;
    private final Long selected;
    private final ComponentSelectionReason reason;
    private final ModuleVersionResolveException failure;
    private final boolean constraint;
    private final ResolvedVariantResult fromVariant;
    private final ResolvedVariantResult targetVariant;

    public DetachedResolvedGraphDependency(ComponentSelector requested,
                                           Long selected,
                                           ComponentSelectionReason reason,
                                           ModuleVersionResolveException failure,
                                           boolean constraint,
                                           ResolvedVariantResult fromVariant,
                                           ResolvedVariantResult targetVariant
    ) {
        assert requested != null;
        assert failure != null || selected != null;

        this.requested = requested;
        this.reason = reason;
        this.selected = selected;
        this.failure = failure;
        this.constraint = constraint;
        this.fromVariant = fromVariant;
        this.targetVariant = targetVariant;
    }

    @Override
    public ComponentSelector getRequested() {
        return requested;
    }

    @Override
    public Long getSelected() {
        return selected;
    }

    @Override
    public ComponentSelectionReason getReason() {
        return reason;
    }

    @Override
    public ModuleVersionResolveException getFailure() {
        return failure;
    }

    @Override
    public boolean isConstraint() {
        return constraint;
    }

    @Override
    public ResolvedVariantResult getFromVariant() {
        return fromVariant;
    }

    @Override
    public ResolvedVariantResult getSelectedVariant() {
        return targetVariant;
    }
}
