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

package com.tyron.builder.api.internal.artifacts.transform;

import com.tyron.builder.api.file.FileCollection;

import java.util.Optional;

public class DefaultArtifactTransformDependencies implements ArtifactTransformDependencies {
    private final FileCollection files;

    public DefaultArtifactTransformDependencies(FileCollection files) {
        this.files = files;
    }

    @Override
    public Optional<FileCollection> getFiles() {
        return Optional.of(files);
    }
}
