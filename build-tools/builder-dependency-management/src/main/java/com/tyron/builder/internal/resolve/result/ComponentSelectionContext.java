/*
 * Copyright 2015 the original author or authors.
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

import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector;
import com.tyron.builder.api.internal.artifacts.repositories.ArtifactResolutionDetails;
import com.tyron.builder.internal.resolve.ModuleVersionResolveException;
import com.tyron.builder.internal.resolve.RejectedByAttributesVersion;
import com.tyron.builder.internal.resolve.RejectedByRuleVersion;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;
import com.tyron.builder.api.internal.attributes.ImmutableAttributes;

import javax.annotation.Nullable;

/**
 * The result of resolving some dynamic version selector to a particular component id.
 */
public interface ComponentSelectionContext {

    /**
     * Marks the given module component identifier as matching.
     *
     * @param moduleComponentIdentifier Chosen module component identifier
     */
    void matches(ModuleComponentIdentifier moduleComponentIdentifier);

    void failed(ModuleVersionResolveException failure);

    /**
     * Registers that there was no matching module component identifier.
     */
    void noMatchFound();

    /**
     * Adds a candidate version that did not match the provided selector.
     */
    void notMatched(ModuleComponentIdentifier id, VersionSelector requestedVersionMatcher);

    /**
     * Adds a candidate version that matched the provided selector, but was rejected by some rule.
     */
    void rejectedByRule(RejectedByRuleVersion id);

    /**
     * Adds a candidate version that matched the provided selector, but was rejected by some constraint.
     */
    void rejectedBySelector(ModuleComponentIdentifier id, VersionSelector versionSelector);

    /**
     * Adds a candidate that matched the provided selector, but was rejected because it didn't match the consumer attributes.
     * @param rejectedVersion a version rejected by attribute matching
     */
    void doesNotMatchConsumerAttributes(RejectedByAttributesVersion rejectedVersion);

    /**
     * Returns the repository content filter, if any.
     */
    @Nullable
    Action<? super ArtifactResolutionDetails> getContentFilter();

    /**
     * Returns the name of the configuration being resolved
     */
    String getConfigurationName();

    /**
     * Returns the consumer attributes
     */
    ImmutableAttributes getConsumerAttributes();
}
