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

package com.tyron.builder.api.internal.artifacts.transform;

import com.tyron.builder.api.artifacts.transform.TransformAction;
import com.tyron.builder.api.internal.tasks.properties.InspectionScheme;
import com.tyron.builder.api.internal.tasks.properties.TypeMetadataStore;
import com.tyron.builder.api.internal.tasks.properties.TypeScheme;
import com.tyron.builder.internal.instantiation.InstantiationScheme;

public class ArtifactTransformActionScheme implements TypeScheme {
    private final InstantiationScheme instantiationScheme;
    private final InspectionScheme inspectionScheme;
    private final InstantiationScheme legacyInstantiationScheme;

    public ArtifactTransformActionScheme(InstantiationScheme instantiationScheme, InspectionScheme inspectionScheme, InstantiationScheme legacyInstantiationScheme) {
        this.instantiationScheme = instantiationScheme;
        this.inspectionScheme = inspectionScheme;
        this.legacyInstantiationScheme = legacyInstantiationScheme;
    }

    @Override
    public TypeMetadataStore getMetadataStore() {
        return inspectionScheme.getMetadataStore();
    }

    @Override
    public boolean appliesTo(Class<?> type) {
        return TransformAction.class.isAssignableFrom(type);
    }

    public InspectionScheme getInspectionScheme() {
        return inspectionScheme;
    }

    public InstantiationScheme getInstantiationScheme() {
        return instantiationScheme;
    }

    public InstantiationScheme getLegacyInstantiationScheme() {
        return legacyInstantiationScheme;
    }
}


