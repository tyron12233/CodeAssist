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

package com.tyron.builder.internal.component.external.model;

import com.tyron.builder.api.artifacts.ArtifactIdentifier;
import com.tyron.builder.internal.component.model.ComponentArtifactMetadata;

/**
 * Meta-data for an artifact that belongs to some module version.
 */
public interface ModuleComponentArtifactMetadata extends ComponentArtifactMetadata {
    @Override
    ModuleComponentArtifactIdentifier getId();

    /**
     * Produces an ArtifactIdentifier for this artifact (it's not actually an identifier - just a bucket of attributes).
     * TODO:ADAM - remove this
     */
    ArtifactIdentifier toArtifactIdentifier();
}
