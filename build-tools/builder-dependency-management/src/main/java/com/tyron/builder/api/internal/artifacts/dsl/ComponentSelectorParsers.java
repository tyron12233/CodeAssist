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

package com.tyron.builder.api.internal.artifacts.dsl;

import com.tyron.builder.api.IllegalDependencyNotation;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.artifacts.component.ComponentSelector;
import com.tyron.builder.api.artifacts.component.ModuleComponentSelector;
import com.tyron.builder.api.internal.artifacts.DefaultModuleIdentifier;
import com.tyron.builder.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint;
import com.tyron.builder.internal.component.local.model.DefaultProjectComponentSelector;
import com.tyron.builder.internal.exceptions.DiagnosticsVisitor;
import com.tyron.builder.internal.typeconversion.MapKey;
import com.tyron.builder.internal.typeconversion.MapNotationConverter;
import com.tyron.builder.internal.typeconversion.NotationConvertResult;
import com.tyron.builder.internal.typeconversion.NotationConverter;
import com.tyron.builder.internal.typeconversion.NotationParser;
import com.tyron.builder.internal.typeconversion.NotationParserBuilder;
import com.tyron.builder.internal.typeconversion.TypeConversionException;

import java.util.Set;

import static com.tyron.builder.internal.component.external.model.DefaultModuleComponentSelector.newSelector;

public class ComponentSelectorParsers {

    private static final NotationParserBuilder<Object, ComponentSelector> BUILDER = NotationParserBuilder
            .toType(ComponentSelector.class)
            .fromCharSequence(new StringConverter())
            .converter(new MapConverter())
            .fromType(BuildProject.class, new ProjectConverter());

    public static NotationParser<Object, Set<ComponentSelector>> multiParser() {
        return builder().toFlatteningComposite();
    }

    public static NotationParser<Object, ComponentSelector> parser() {
        return builder().toComposite();
    }

    private static NotationParserBuilder<Object, ComponentSelector> builder() {
        return BUILDER;
    }

    static class MapConverter extends MapNotationConverter<ComponentSelector> {
        @Override
        public void describe(DiagnosticsVisitor visitor) {
            visitor.example("Maps, e.g. [group: 'com.tyron.builder', name:'gradle-core', version: '1.0'].");
        }

        protected ModuleComponentSelector parseMap(@MapKey("group") String group, @MapKey("name") String name, @MapKey("version") String version) {
            return newSelector(DefaultModuleIdentifier.newId(group, name),
                    DefaultImmutableVersionConstraint
                    .of(version));
        }
    }

    static class StringConverter implements NotationConverter<String, ComponentSelector> {
        @Override
        public void describe(DiagnosticsVisitor visitor) {
            visitor.example("String or CharSequence values, e.g. 'com.tyron.builder:gradle-core:1.0'.");
        }

        @Override
        public void convert(String notation, NotationConvertResult<? super ComponentSelector> result) throws TypeConversionException {
            ParsedModuleStringNotation parsed;
            try {
                parsed = new ParsedModuleStringNotation(notation, null);
            } catch (IllegalDependencyNotation e) {
                throw new InvalidUserDataException(
                        "Invalid format: '" + notation + "'. The correct notation is a 3-part group:name:version notation, "
                                + "e.g: 'com.tyron.builder:gradle-core:1.0'");
            }

            if (parsed.getGroup() == null || parsed.getName() == null || parsed.getVersion() == null) {
                throw new InvalidUserDataException(
                        "Invalid format: '" + notation + "'. Group, name and version cannot be empty. Correct example: "
                                + "'com.tyron.builder:gradle-core:1.0'");
            }
            result.converted(newSelector(DefaultModuleIdentifier.newId(parsed.getGroup(), parsed.getName()), DefaultImmutableVersionConstraint.of(parsed.getVersion())));
        }
    }

    static class ProjectConverter implements NotationConverter<BuildProject, ComponentSelector> {

        @Override
        public void describe(DiagnosticsVisitor visitor) {
            visitor.example("Project objects, e.g. project(':api').");
        }

        @Override
        public void convert(BuildProject notation, NotationConvertResult<? super ComponentSelector> result) throws TypeConversionException {
            result.converted(DefaultProjectComponentSelector.newSelector(notation));
        }
    }
}
