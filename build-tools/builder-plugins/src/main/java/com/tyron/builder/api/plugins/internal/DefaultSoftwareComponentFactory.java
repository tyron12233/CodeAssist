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
package com.tyron.builder.api.plugins.internal;

import com.tyron.builder.api.component.AdhocComponentWithVariants;
import com.tyron.builder.api.component.SoftwareComponentFactory;
import com.tyron.builder.internal.reflect.Instantiator;

public class DefaultSoftwareComponentFactory implements SoftwareComponentFactory {

    private final Instantiator instantiator;

    public DefaultSoftwareComponentFactory(Instantiator instantiator) {
        this.instantiator = instantiator;
    }

    @Override
    public AdhocComponentWithVariants adhoc(String name) {
        return instantiator.newInstance(DefaultAdhocSoftwareComponent.class, name, instantiator);
    }
}
