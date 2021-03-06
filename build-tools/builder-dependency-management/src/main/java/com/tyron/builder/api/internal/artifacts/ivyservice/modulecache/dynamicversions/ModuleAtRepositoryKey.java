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
package com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.dynamicversions;

import com.tyron.builder.api.artifacts.ModuleIdentifier;

class ModuleAtRepositoryKey {
    final String repositoryId;
    final ModuleIdentifier moduleId;

    ModuleAtRepositoryKey(String repositoryId, ModuleIdentifier moduleId) {
        this.repositoryId = repositoryId;
        this.moduleId = moduleId;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ModuleAtRepositoryKey)) {
            return false;
        }
        ModuleAtRepositoryKey other = (ModuleAtRepositoryKey) o;
        return repositoryId.equals(other.repositoryId) && moduleId.equals(other.moduleId);
    }

    @Override
    public int hashCode() {
        return repositoryId.hashCode() ^ moduleId.hashCode();
    }
}
