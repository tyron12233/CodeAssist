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

package com.tyron.builder.model.internal.manage.schema;

import com.tyron.builder.model.internal.type.ModelType;

/**
 * A schema for an element that contains zero or more elements.
 */
public class CollectionSchema<T, E> extends AbstractModelSchema<T> implements ManagedImplSchema<T> {
    private final ModelType<E> elementType;
    private ModelSchema<E> elementTypeSchema;

    public CollectionSchema(ModelType<T> type, ModelType<E> elementType) {
        super(type);
        this.elementType = elementType;
    }

    public ModelType<E> getElementType() {
        return elementType;
    }

    public ModelSchema<E> getElementTypeSchema() {
        return elementTypeSchema;
    }

    @Override
    public String toString() {
        return "collection " + getType();
    }

    public void setElementTypeSchema(ModelSchema<E> elementTypeSchema) {
        this.elementTypeSchema = elementTypeSchema;
    }
}
