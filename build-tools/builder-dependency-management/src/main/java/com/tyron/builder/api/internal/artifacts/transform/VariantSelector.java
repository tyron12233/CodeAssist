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

package com.tyron.builder.api.internal.artifacts.transform;

import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactVisitor;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariant;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariantSet;

import com.tyron.builder.api.internal.attributes.ImmutableAttributes;

public interface VariantSelector {
    /**
     * Selects matching artifacts from a given set of candidates.
     *
     * On failure, returns a set that forwards the failure to the {@link ArtifactVisitor}.
     */
    ResolvedArtifactSet select(ResolvedVariantSet candidates, Factory factory);

    ImmutableAttributes getRequestedAttributes();

    interface Factory {
        ResolvedArtifactSet asTransformed(ResolvedVariant sourceVariant, VariantDefinition variantDefinition, ExtraExecutionGraphDependenciesResolverFactory dependenciesResolver, TransformedVariantFactory transformedVariantFactory);
    }
}
