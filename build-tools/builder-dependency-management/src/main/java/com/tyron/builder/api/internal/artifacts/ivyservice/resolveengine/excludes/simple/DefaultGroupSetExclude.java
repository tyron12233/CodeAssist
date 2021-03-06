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
package com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.simple;

import com.tyron.builder.api.artifacts.ModuleIdentifier;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.GroupSetExclude;
import com.tyron.builder.internal.component.model.IvyArtifactName;

import java.util.Set;

final class DefaultGroupSetExclude implements GroupSetExclude {
    private final Set<String> groups;
    private final int hashCode;

    DefaultGroupSetExclude(Set<String> groups) {
        this.groups = groups;
        this.hashCode = groups.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DefaultGroupSetExclude that = (DefaultGroupSetExclude) o;

        return groups.equals(that.groups);

    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public Set<String> getGroups() {
        return groups;
    }

    @Override
    public boolean excludes(ModuleIdentifier module) {
        return groups.contains(module.getGroup());
    }

    @Override
    public boolean excludesArtifact(ModuleIdentifier module, IvyArtifactName artifactName) {
        return false;
    }

    @Override
    public boolean mayExcludeArtifacts() {
        return false;
    }

    @Override
    public String toString() {
        return "{ \"groups\" : [" + ExcludeJsonHelper.toJson(groups) + "]}";
    }

}
