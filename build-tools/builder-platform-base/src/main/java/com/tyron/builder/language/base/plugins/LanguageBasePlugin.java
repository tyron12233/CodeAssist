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
package com.tyron.builder.language.base.plugins;

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.language.base.LanguageSourceSet;
import com.tyron.builder.language.base.ProjectSourceSet;
import com.tyron.builder.language.base.internal.DefaultProjectSourceSet;
import com.tyron.builder.language.base.internal.LanguageSourceSetInternal;
import com.tyron.builder.language.base.sources.BaseLanguageSourceSet;
import com.tyron.builder.model.Model;
import com.tyron.builder.model.RuleSource;
import com.tyron.builder.platform.base.ComponentType;
import com.tyron.builder.platform.base.TypeBuilder;
import com.tyron.builder.platform.base.plugins.ComponentBasePlugin;

/**
 * Base plugin for language support.
 *
 * - Adds a {@link ProjectSourceSet} named {@code sources} to the project.
 * - Registers the base {@link LanguageSourceSet} type.
 */
@Incubating
public class LanguageBasePlugin implements Plugin<BuildProject> {
    @Override
    public void apply(BuildProject project) {
        project.getPluginManager().apply(ComponentBasePlugin.class);
    }

    @SuppressWarnings("UnusedDeclaration")
    static class Rules extends RuleSource {
        @ComponentType
        void registerBaseLanguageSourceSet(TypeBuilder<LanguageSourceSet> builder) {
            builder.defaultImplementation(BaseLanguageSourceSet.class);
            builder.internalView(LanguageSourceSetInternal.class);
        }

        @Model
        ProjectSourceSet sources(Instantiator instantiator, CollectionCallbackActionDecorator decorator) {
            return instantiator.newInstance(DefaultProjectSourceSet.class, decorator);
        }
    }
}
