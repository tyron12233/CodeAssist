/*
 * Copyright 2014 the original author or authors.
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

import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.ComponentModuleMetadataDetails;
import com.tyron.builder.api.artifacts.dsl.ComponentModuleMetadataHandler;
import com.tyron.builder.api.internal.artifacts.ComponentModuleMetadataProcessor;
import com.tyron.builder.api.internal.artifacts.ImmutableModuleIdentifierFactory;

public class DefaultComponentModuleMetadataHandler implements ComponentModuleMetadataHandler, ComponentModuleMetadataProcessor {
    private final ComponentModuleMetadataContainer moduleMetadataContainer;

    public DefaultComponentModuleMetadataHandler(ImmutableModuleIdentifierFactory moduleIdentifierFactory) {
        this.moduleMetadataContainer = new ComponentModuleMetadataContainer(moduleIdentifierFactory);
    }

    @Override
    public void module(Object moduleNotation, Action<? super ComponentModuleMetadataDetails> rule) {
        rule.execute(moduleMetadataContainer.module(moduleNotation));
    }

    @Override
    public ModuleReplacementsData getModuleReplacements() {
        return moduleMetadataContainer;
    }
}
