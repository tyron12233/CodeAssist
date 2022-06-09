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

import com.tyron.builder.api.internal.artifacts.ArtifactTransformRegistration;

import com.tyron.builder.api.artifacts.transform.TransformAction;
import com.tyron.builder.api.artifacts.transform.TransformParameters;
import com.tyron.builder.api.internal.attributes.ImmutableAttributes;

import javax.annotation.Nullable;

public interface TransformationRegistrationFactory {
    ArtifactTransformRegistration create(ImmutableAttributes from, ImmutableAttributes to, Class<? extends TransformAction<?>> implementation, @Nullable TransformParameters parameterObject);
    @SuppressWarnings("deprecation")
    ArtifactTransformRegistration create(ImmutableAttributes from, ImmutableAttributes to, Class<? extends com.tyron.builder.api.artifacts.transform.ArtifactTransform> implementation, Object[] params);
}
