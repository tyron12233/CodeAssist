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
package com.tyron.builder.internal.resource.local.ivy;

import com.tyron.builder.api.internal.artifacts.repositories.resolver.ResourcePattern;
import com.tyron.builder.internal.component.external.model.ModuleComponentArtifactMetadata;

import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.file.EmptyFileVisitor;
import com.tyron.builder.api.file.FileVisitDetails;
import com.tyron.builder.internal.hash.ChecksumService;
import com.tyron.builder.internal.resource.local.AbstractLocallyAvailableResourceFinder;
import com.tyron.builder.api.internal.file.collections.MinimalFileTree;
import com.tyron.builder.api.internal.file.collections.SingleIncludePatternFileTree;
import com.tyron.builder.internal.Factory;

import java.io.File;
import java.util.LinkedList;
import java.util.List;

public class PatternBasedLocallyAvailableResourceFinder extends AbstractLocallyAvailableResourceFinder<ModuleComponentArtifactMetadata> {

    public PatternBasedLocallyAvailableResourceFinder(File baseDir, ResourcePattern pattern, ChecksumService checksumService) {
        super(createProducer(baseDir, pattern), checksumService);
    }

    private static Transformer<Factory<List<File>>, ModuleComponentArtifactMetadata> createProducer(final File baseDir, final ResourcePattern pattern) {
        return new Transformer<Factory<List<File>>, ModuleComponentArtifactMetadata>() {
            @Override
            public Factory<List<File>> transform(final ModuleComponentArtifactMetadata artifact) {
                return () -> {
                    final List<File> files = new LinkedList<>();
                    if (artifact != null) {
                        getMatchingFiles(artifact).visit(new EmptyFileVisitor() {
                            @Override
                            public void visitFile(FileVisitDetails fileDetails) {
                                files.add(fileDetails.getFile());
                            }
                        });
                    }
                    return files;
                };
            }

            private MinimalFileTree getMatchingFiles(ModuleComponentArtifactMetadata artifact) {
                String patternString = pattern.getLocation(artifact).getPath();
                return new SingleIncludePatternFileTree(baseDir, patternString);
            }

        };
    }
}
