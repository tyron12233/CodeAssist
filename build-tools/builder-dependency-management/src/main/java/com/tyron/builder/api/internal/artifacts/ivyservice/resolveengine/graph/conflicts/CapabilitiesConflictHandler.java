/*
 * Copyright 2018 the original author or authors.
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
package com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.graph.conflicts;

import com.tyron.builder.api.Describable;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.capabilities.Capability;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.graph.builder.NodeState;

import java.util.Collection;

public interface CapabilitiesConflictHandler extends ConflictHandler<CapabilitiesConflictHandler.Candidate, ConflictResolutionResult, CapabilitiesConflictHandler.Resolver> {

    /**
     * Was the given capability already seen which might require a conflict check later?
     * This is needed to determine if also implicit capabilities need to enter conflict detection.
     */
    boolean hasSeenCapability(Capability capability);

    interface Candidate {
        NodeState getNode();
        Capability getCapability();
        Collection<NodeState> getImplicitCapabilityProviders();
    }

    interface ResolutionDetails extends ConflictResolutionResult {
        Collection<? extends Capability> getCapabilityVersions();
        Collection<? extends CandidateDetails> getCandidates(Capability capability);
        boolean hasResult();
    }

    interface CandidateDetails {
        ComponentIdentifier getId();
        String getVariantName();
        void evict();
        void select();
        void reject();
        void byReason(Describable description);
    }

    interface Resolver {
        void resolve(ResolutionDetails details);
    }
}
