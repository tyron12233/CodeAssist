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

import com.google.common.collect.Interner;
import com.tyron.builder.api.internal.artifacts.dsl.ParsedModuleStringNotation;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.ModuleFactoryHelper;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.ClientModule;
import com.tyron.builder.api.artifacts.DependencyConstraint;
import com.tyron.builder.api.artifacts.ExternalDependency;
import com.tyron.builder.api.artifacts.MutableVersionConstraint;
import com.tyron.builder.api.internal.catalog.parser.StrictVersionParser;
import com.tyron.builder.internal.exceptions.DiagnosticsVisitor;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.typeconversion.NotationConvertResult;
import com.tyron.builder.internal.typeconversion.NotationConverter;
import com.tyron.builder.internal.typeconversion.TypeConversionException;

public class DependencyStringNotationConverter<T> implements NotationConverter<String, T> {
    private final Instantiator instantiator;
    private final Class<T> wantedType;
    private final Interner<String> stringInterner;
    private final StrictVersionParser strictVersionParser;

    public DependencyStringNotationConverter(Instantiator instantiator, Class<T> wantedType, Interner<String> stringInterner) {
        this.instantiator = instantiator;
        this.wantedType = wantedType;
        this.stringInterner = stringInterner;
        this.strictVersionParser = new StrictVersionParser(stringInterner);
    }

    @Override
    public void describe(DiagnosticsVisitor visitor) {
        visitor.candidate("String or CharSequence values").example("'com.tyron.builder:gradle-core:1.0'");
    }

    @Override
    public void convert(String notation, NotationConvertResult<? super T> result) throws TypeConversionException {
        result.converted(createDependencyFromString(notation));
    }

    private T createDependencyFromString(String notation) {

        ParsedModuleStringNotation parsedNotation = splitModuleFromExtension(notation);
        StrictVersionParser.RichVersion version = strictVersionParser.parse(parsedNotation.getVersion());
        T moduleDependency = instantiator.newInstance(wantedType,
            stringInterner.intern(parsedNotation.getGroup()), stringInterner.intern(parsedNotation.getName()), stringInterner.intern(version.require));
        maybeEnrichVersion(version, moduleDependency);
        if (moduleDependency instanceof ExternalDependency) {
            ModuleFactoryHelper.addExplicitArtifactsIfDefined((ExternalDependency) moduleDependency, parsedNotation.getArtifactType(), parsedNotation.getClassifier());
        }

        return moduleDependency;
    }

    private void maybeEnrichVersion(StrictVersionParser.RichVersion version, T moduleDependency) {
        if (version.strictly != null) {
            Action<MutableVersionConstraint> versionAction = v -> {
                v.strictly(version.strictly);
                if (!version.prefer.isEmpty()) {
                    v.prefer(version.prefer);
                }
            };
            if (moduleDependency instanceof ExternalDependency) {
                ((ExternalDependency) moduleDependency).version(versionAction);
            }
            if (moduleDependency instanceof DependencyConstraint) {
                ((DependencyConstraint) moduleDependency).version(versionAction);
            }
        }
    }

    private ParsedModuleStringNotation splitModuleFromExtension(String notation) {
        int idx = notation.lastIndexOf('@');
        if (idx == -1 || ClientModule.class.isAssignableFrom(wantedType)) {
            return new ParsedModuleStringNotation(notation, null);
        }
        int versionIndx = notation.lastIndexOf(':');
        if (versionIndx < idx) {
            return new ParsedModuleStringNotation(notation.substring(0, idx), notation.substring(idx + 1));
        }
        return new ParsedModuleStringNotation(notation, null);
    }
}
