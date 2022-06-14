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

package com.tyron.builder.tooling.internal.consumer.converters;

import com.tyron.builder.tooling.model.DomainObjectSet;
import com.tyron.builder.tooling.model.gradle.GradleBuild;
import com.tyron.builder.tooling.model.internal.ImmutableDomainObjectSet;

import java.io.Serializable;
import java.util.Collections;

public class IncludedBuildsMixin implements Serializable {
    private final GradleBuild gradleBuild;

    public IncludedBuildsMixin(GradleBuild gradleBuild) {
        this.gradleBuild = gradleBuild;
    }

    public DomainObjectSet<? extends GradleBuild> getIncludedBuilds() {
        return ImmutableDomainObjectSet.of(Collections.<GradleBuild>emptyList());
    }

    public DomainObjectSet<? extends GradleBuild> getEditableBuilds() {
        return gradleBuild.getIncludedBuilds();
    }
}
