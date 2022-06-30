/*
 * Copyright 2009 the original author or authors.
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

import com.tyron.builder.api.artifacts.SelfResolvingDependency;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.artifacts.dependencies.DefaultSelfResolvingDependency;
import com.tyron.builder.internal.exceptions.DiagnosticsVisitor;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.typeconversion.NotationConvertResult;
import com.tyron.builder.internal.typeconversion.NotationConverter;
import com.tyron.builder.internal.typeconversion.TypeConversionException;

public class DependencyFilesNotationConverter implements NotationConverter<FileCollection, SelfResolvingDependency> {
    private final Instantiator instantiator;

    public DependencyFilesNotationConverter(Instantiator instantiator) {
        this.instantiator = instantiator;
    }

    @Override
    public void describe(DiagnosticsVisitor visitor) {
        visitor.candidate("FileCollections").example("files('some.jar', 'someOther.jar')");
    }

    @Override
    public void convert(FileCollection notation, NotationConvertResult<? super SelfResolvingDependency> result) throws TypeConversionException {
        result.converted(instantiator.newInstance(DefaultSelfResolvingDependency.class, notation));
    }
}
