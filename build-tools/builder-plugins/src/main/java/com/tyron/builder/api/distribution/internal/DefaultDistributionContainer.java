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
package com.tyron.builder.api.distribution.internal;

import com.tyron.builder.api.distribution.Distribution;
import com.tyron.builder.api.distribution.DistributionContainer;
import com.tyron.builder.api.internal.AbstractNamedDomainObjectContainer;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.file.FileOperations;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.internal.reflect.Instantiator;

import javax.inject.Inject;

/**
 * Default implementation for {@link com.tyron.builder.api.distribution.DistributionContainer}
 */
public class DefaultDistributionContainer extends AbstractNamedDomainObjectContainer<Distribution> implements DistributionContainer {
    private final ObjectFactory objectFactory;
    private final FileOperations fileOperations;

    @Inject
    public DefaultDistributionContainer(Class<Distribution> type, Instantiator instantiator, ObjectFactory objectFactory, FileOperations fileOperations, CollectionCallbackActionDecorator callbackDecorator) {
        super(type, instantiator, callbackDecorator);
        this.objectFactory = objectFactory;
        this.fileOperations = fileOperations;
    }

    @Override
    protected Distribution doCreate(String name) {
        return objectFactory.newInstance(DefaultDistribution.class, name, fileOperations.copySpec());
    }
}
