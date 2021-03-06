/*
 * Copyright 2018 the original author or authors.
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
package com.tyron.builder.api.internal.artifacts.repositories.metadata;

import com.tyron.builder.api.attributes.Attribute;
import com.tyron.builder.api.attributes.Category;
import com.tyron.builder.api.attributes.LibraryElements;
import com.tyron.builder.api.attributes.Usage;
import com.tyron.builder.api.internal.attributes.ImmutableAttributes;
import com.tyron.builder.api.internal.attributes.ImmutableAttributesFactory;

/**
 * A specialized attributes factory for Maven metadata. The specialized methods take advantage
 * of the fact we know that for derived variants, we're going to see almost always the same input
 * attributes, and the same mutations to make on them, so it's more efficient to map them, than
 * recomputing each time.
 */
public interface MavenImmutableAttributesFactory extends ImmutableAttributesFactory {
    // We need to work with the 'String' version of the usage attribute, since this is expected for all providers by the `PreferJavaRuntimeVariant` schema
    Attribute<String> USAGE_ATTRIBUTE = Attribute.of(Usage.USAGE_ATTRIBUTE.getName(), String.class);
    Attribute<String> FORMAT_ATTRIBUTE = Attribute.of(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE.getName(), String.class);
    Attribute<String> CATEGORY_ATTRIBUTE = Attribute.of(Category.CATEGORY_ATTRIBUTE.getName(), String.class);

    ImmutableAttributes libraryWithUsage(ImmutableAttributes original, String usage);
    ImmutableAttributes platformWithUsage(ImmutableAttributes original, String usage, boolean enforced);
}
