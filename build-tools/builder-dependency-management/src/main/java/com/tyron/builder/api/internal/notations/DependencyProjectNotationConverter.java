/*
 * Copyright 2011 the original author or authors.
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

package com.tyron.builder.api.internal.notations;

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.artifacts.ProjectDependency;
import com.tyron.builder.api.internal.artifacts.DefaultProjectDependencyFactory;
import com.tyron.builder.internal.exceptions.DiagnosticsVisitor;
import com.tyron.builder.internal.typeconversion.NotationConvertResult;
import com.tyron.builder.internal.typeconversion.NotationConverter;
import com.tyron.builder.internal.typeconversion.TypeConversionException;

public class DependencyProjectNotationConverter implements NotationConverter<BuildProject,
        ProjectDependency> {

    private final DefaultProjectDependencyFactory factory;

    public DependencyProjectNotationConverter(DefaultProjectDependencyFactory factory) {
        this.factory = factory;
    }

    @Override
    public void describe(DiagnosticsVisitor visitor) {
        visitor.candidate("Projects").example("project(':some:project:path')");
    }

    @Override
    public void convert(BuildProject notation,
                        NotationConvertResult<? super ProjectDependency> result) throws TypeConversionException {
        result.converted(factory.create(notation));
    }
}
