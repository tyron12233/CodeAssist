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

package com.tyron.builder.internal.component.external.model;

import com.tyron.builder.api.artifacts.ComponentVariantIdentifier;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;

public class DefaultComponentVariantIdentifier implements ComponentVariantIdentifier {

    private final ComponentIdentifier id;
    private final String variantName;

    public DefaultComponentVariantIdentifier(ComponentIdentifier id, String variantName) {
        this.id = id;
        this.variantName = variantName;
    }

    @Override
    public ComponentIdentifier getId() {
        return id;
    }

    @Override
    public String getVariantName() {
        return variantName;
    }

    @Override
    public String toString() {
        return id + "(" + variantName + ")";
    }
}
