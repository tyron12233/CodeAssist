/*
 * Copyright 2016 the original author or authors.
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

package com.tyron.builder.platform.base;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.internal.HasInternalProtocol;
import com.tyron.builder.language.base.LanguageSourceSet;
import com.tyron.builder.model.ModelMap;

/**
 * Represents some component whose implementation can be represented as a collection of source files, and whose other
 * outputs are built from this source.
 */
@Incubating
@HasInternalProtocol
public interface SourceComponentSpec extends ComponentSpec {
    /**
     * The source sets for this component.
     */
    ModelMap<LanguageSourceSet> getSources();
}
