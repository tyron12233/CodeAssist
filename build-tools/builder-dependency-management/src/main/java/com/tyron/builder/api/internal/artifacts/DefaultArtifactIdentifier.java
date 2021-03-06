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
package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.artifacts.ArtifactIdentifier;
import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.internal.component.external.model.DefaultModuleComponentArtifactIdentifier;

import javax.annotation.Nullable;
import java.util.Objects;

import static com.tyron.builder.api.internal.artifacts.DefaultModuleVersionIdentifier.newId;

public class DefaultArtifactIdentifier implements ArtifactIdentifier {
    private final ModuleVersionIdentifier moduleVersionIdentifier;
    private final String name;
    private final String type;
    private final String extension;
    private final String classifier;

    public DefaultArtifactIdentifier(ModuleVersionIdentifier moduleVersionIdentifier, String name, String type, @Nullable String extension, @Nullable String classifier) {
        this.moduleVersionIdentifier = moduleVersionIdentifier;
        this.name = name;
        this.type = type;
        this.extension = extension;
        this.classifier = classifier;
    }

    public DefaultArtifactIdentifier(DefaultModuleComponentArtifactIdentifier id) {
        this(DefaultModuleVersionIdentifier.newId(id.getComponentIdentifier()), id.getName().getName(), id.getName().getType(), id.getName().getExtension(), id.getName().getClassifier());
    }

    @Override
    public ModuleVersionIdentifier getModuleVersionIdentifier() {
        return moduleVersionIdentifier;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public String getExtension() {
        return extension;
    }

    @Override
    public String getClassifier() {
        return classifier;
    }

    @Override
    public String toString() {
        return String.format("module: %s, name: %s, ext: %s, classifier: %s", moduleVersionIdentifier, name, extension, classifier);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DefaultArtifactIdentifier)) {
            return false;
        }

        DefaultArtifactIdentifier that = (DefaultArtifactIdentifier) o;

        if (!Objects.equals(classifier, that.classifier)) {
            return false;
        }
        if (!Objects.equals(extension, that.extension)) {
            return false;
        }
        if (!Objects.equals(moduleVersionIdentifier, that.moduleVersionIdentifier)) {
            return false;
        }
        if (!Objects.equals(name, that.name)) {
            return false;
        }
        return Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() {
        int result = moduleVersionIdentifier != null ? moduleVersionIdentifier.hashCode() : 0;
        result = 31 * result + (name != null ? name.hashCode() : 0);
        result = 31 * result + (type != null ? type.hashCode() : 0);
        result = 31 * result + (extension != null ? extension.hashCode() : 0);
        result = 31 * result + (classifier != null ? classifier.hashCode() : 0);
        return result;
    }
}
