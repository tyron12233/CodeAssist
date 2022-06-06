/*
 * Copyright 2016 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact;

import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.internal.artifacts.transform.VariantSelector;

import java.util.function.Predicate;

public interface VisitedArtifactsResults {
    /**
     * Selects the artifacts for the matching variant of each node seen during traversal. The implementation should attempt to select artifacts eagerly, but may be lazy where the selection cannot happen until the results are queried.
     */
    SelectedArtifactResults select(Predicate<? super ComponentIdentifier> componentFilter, VariantSelector selector);
}
