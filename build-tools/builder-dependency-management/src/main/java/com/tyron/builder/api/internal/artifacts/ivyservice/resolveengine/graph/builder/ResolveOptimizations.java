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
package com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.graph.builder;

/**
 * This class is used to store state enabling optimizations
 * during dependency resolution. It's isolated to make it very
 * clear those are optimizations that can be removed at some
 * point.
 */
public class ResolveOptimizations {
    private boolean hasVirtualPlatforms;
    private boolean hasForcedPlatforms;

    void declareForcedPlatformInUse() {
        hasForcedPlatforms = true;
    }

    void declareVirtualPlatformInUse() {
        hasVirtualPlatforms = true;
    }

    public boolean mayHaveForcedPlatforms() {
        return hasForcedPlatforms;
    }


    public boolean mayHaveVirtualPlatforms() {
        return hasVirtualPlatforms;
    }
}
