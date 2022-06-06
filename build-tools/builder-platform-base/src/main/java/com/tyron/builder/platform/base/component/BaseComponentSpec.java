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

package com.tyron.builder.platform.base.component;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.language.base.LanguageSourceSet;
import com.tyron.builder.model.ModelMap;
import com.tyron.builder.model.internal.core.ModelMaps;
import com.tyron.builder.model.internal.core.MutableModelNode;
import com.tyron.builder.model.internal.type.ModelType;
import com.tyron.builder.platform.base.Binary;
import com.tyron.builder.platform.base.BinarySpec;
import com.tyron.builder.platform.base.GeneralComponentSpec;
import com.tyron.builder.platform.base.component.internal.DefaultComponentSpec;

/**
 * Base class that may be used for custom {@link GeneralComponentSpec} implementations. However, it is generally better to use an
 * interface annotated with {@link com.tyron.builder.model.Managed} and not use an implementation class at all.
 */
@Incubating
public class BaseComponentSpec extends DefaultComponentSpec implements GeneralComponentSpec {
    private static final ModelType<BinarySpec> BINARY_SPEC_MODEL_TYPE = ModelType.of(BinarySpec.class);
    private static final ModelType<Binary> BINARY_MODEL_TYPE = ModelType.of(Binary.class);
    private static final ModelType<LanguageSourceSet> LANGUAGE_SOURCE_SET_MODEL_TYPE = ModelType.of(LanguageSourceSet.class);
    private final MutableModelNode binaries;
    private final MutableModelNode sources;

    public BaseComponentSpec() {
        MutableModelNode modelNode = getInfo().modelNode;
        binaries = ModelMaps.addModelMapNode(modelNode, BINARY_SPEC_MODEL_TYPE, "binaries");
        sources = ModelMaps.addModelMapNode(modelNode, LANGUAGE_SOURCE_SET_MODEL_TYPE, "sources");
    }

    @Override
    public ModelMap<LanguageSourceSet> getSources() {
        return ModelMaps.toView(sources, LANGUAGE_SOURCE_SET_MODEL_TYPE);
    }

    @Override
    public ModelMap<BinarySpec> getBinaries() {
        return ModelMaps.toView(binaries, BINARY_SPEC_MODEL_TYPE);
    }

    @Override
    public Iterable<Binary> getVariants() {
        return ModelMaps.toView(binaries, BINARY_MODEL_TYPE);
    }
}
