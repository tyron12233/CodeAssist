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
package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.Namer;
import com.tyron.builder.api.internal.AbstractValidatingNamedDomainObjectContainer;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.reflect.TypeOf;
import com.tyron.builder.api.tasks.SourceSet;
import com.tyron.builder.api.tasks.SourceSetContainer;
import com.tyron.builder.internal.reflect.Instantiator;

import javax.inject.Inject;

import static com.tyron.builder.api.reflect.TypeOf.typeOf;

public class DefaultSourceSetContainer extends AbstractValidatingNamedDomainObjectContainer<SourceSet> implements SourceSetContainer {
    private final ObjectFactory objectFactory;
    private final FileResolver fileResolver;
    private final FileCollectionFactory fileCollectionFactory;
    private final Instantiator instantiator;

    @Inject
    public DefaultSourceSetContainer(FileResolver fileResolver, FileCollectionFactory fileCollectionFactory, Instantiator instantiator, ObjectFactory objectFactory, CollectionCallbackActionDecorator collectionCallbackActionDecorator) {
        super(SourceSet.class, instantiator, SourceSet::getName, collectionCallbackActionDecorator);
        this.fileResolver = fileResolver;
        this.fileCollectionFactory = fileCollectionFactory;
        this.instantiator = instantiator;
        this.objectFactory = objectFactory;
    }

    @Override
    protected SourceSet doCreate(String name) {
        DefaultSourceSet sourceSet = instantiator.newInstance(DefaultSourceSet.class, name, objectFactory);
        sourceSet.setClasses(instantiator.newInstance(DefaultSourceSetOutput.class, sourceSet.getDisplayName(), fileResolver, fileCollectionFactory));
        return sourceSet;
    }

    @Override
    public TypeOf<?> getPublicType() {
        return typeOf(SourceSetContainer.class);
    }
}
