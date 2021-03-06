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

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.artifacts.transform.InputArtifactDependencies;
import com.tyron.builder.api.internal.tasks.properties.InputFilePropertyType;
import com.tyron.builder.api.internal.tasks.properties.annotations.AbstractInputFilePropertyAnnotationHandler;
import com.tyron.builder.internal.instantiation.InjectAnnotationHandler;
import com.tyron.builder.internal.reflect.AnnotationCategory;

import java.lang.annotation.Annotation;

import static com.tyron.builder.api.internal.tasks.properties.ModifierAnnotationCategory.NORMALIZATION;

public class InputArtifactDependenciesAnnotationHandler extends AbstractInputFilePropertyAnnotationHandler implements InjectAnnotationHandler {
    @Override
    public Class<? extends Annotation> getAnnotationType() {
        return InputArtifactDependencies.class;
    }

    @Override
    public ImmutableSet<? extends AnnotationCategory> getAllowedModifiers() {
        return ImmutableSet.of(NORMALIZATION);
    }

    @Override
    protected InputFilePropertyType getFilePropertyType() {
        return InputFilePropertyType.FILES;
    }
}
