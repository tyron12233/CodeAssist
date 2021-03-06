/*
 * Copyright 2012 the original author or authors.
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

package com.tyron.builder.api.distribution;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Named;
import com.tyron.builder.api.file.CopySpec;
import com.tyron.builder.api.provider.Property;

/**
 * A distribution allows to bundle an application or a library including dependencies, sources...
 */
public interface Distribution extends Named {

    /**
     * The name of this distribution.
     */
    @Override
    String getName();

    /**
     * The baseName of the distribution, used in naming the distribution archives.
     * <p>
     * If the {@link #getName()} of this distribution is "{@code main}" this defaults to the project's name.
     * Otherwise it is "{@code $project.name-$this.name}".
     *
     * @since 6.0
     */
    Property<String> getDistributionBaseName();

    /**
     * The contents of the distribution.
     */
    CopySpec getContents();

    /**
     * Configures the contents of the distribution.
     * <p>
     * Can be used to configure the contents of the distribution:
     * <pre class='autoTested'>
     * plugins {
     *     id 'distribution'
     * }
     *
     * distributions {
     *     main {
     *         contents {
     *             from "src/readme"
     *         }
     *     }
     * }
     * </pre>
     * The DSL inside the {@code contents\{} } block is the same DSL used for Copy tasks.
     */
    CopySpec contents(Action<? super CopySpec> action);
}
