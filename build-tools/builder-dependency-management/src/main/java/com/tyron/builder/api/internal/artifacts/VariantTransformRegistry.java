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

package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.transform.TransformAction;
import com.tyron.builder.api.artifacts.transform.TransformParameters;
import com.tyron.builder.api.artifacts.transform.TransformSpec;

import java.util.List;

public interface VariantTransformRegistry {

    /**
     * Register an artifact transformation.
     *
     * @see com.tyron.builder.api.artifacts.transform.VariantTransform
     * @deprecated Use {@link #registerTransform(Class, Action)} instead
     */
    @Deprecated
    @SuppressWarnings("deprecation")
    void registerTransform(Action<? super com.tyron.builder.api.artifacts.transform.VariantTransform> registrationAction);

    /**
     * Register an artifact transformation.
     *
     * @see TransformAction
     */
    <T extends TransformParameters> void registerTransform(Class<? extends TransformAction<T>> actionType, Action<? super TransformSpec<T>> registrationAction);

    List<ArtifactTransformRegistration> getTransforms();
}
