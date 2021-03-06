/*
 * Copyright 2017 the original author or authors.
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

package com.tyron.builder.internal.component.model;

import com.tyron.builder.api.attributes.Attribute;
import com.tyron.builder.api.internal.attributes.ImmutableAttributes;

import javax.annotation.Nullable;
import java.util.Set;

public interface AttributeSelectionSchema {
    boolean hasAttribute(Attribute<?> attribute);

    Set<Object> disambiguate(Attribute<?> attribute, @Nullable Object requested, Set<Object> candidates);

    boolean matchValue(Attribute<?> attribute, Object requested, Object candidate);

    @Nullable
    Attribute<?> getAttribute(String name);

    Attribute<?>[] collectExtraAttributes(ImmutableAttributes[] candidates, ImmutableAttributes requested);
}
