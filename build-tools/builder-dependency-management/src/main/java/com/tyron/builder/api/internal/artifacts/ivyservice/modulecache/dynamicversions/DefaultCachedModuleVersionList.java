/*
 * Copyright 2011 the original author or authors.
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
package com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.dynamicversions;

import java.time.Duration;
import java.util.Set;

class DefaultCachedModuleVersionList implements ModuleVersionsCache.CachedModuleVersionList {
    private final Set<String> moduleVersions;
    private final long ageMillis;

    public DefaultCachedModuleVersionList(Set<String> moduleVersions, long ageMillis) {
        this.moduleVersions = moduleVersions;
        this.ageMillis = ageMillis;
    }

    @Override
    public Set<String> getModuleVersions() {
        return moduleVersions;
    }

    @Override
    public Duration getAge() {
        return Duration.ofMillis(ageMillis);
    }
}
