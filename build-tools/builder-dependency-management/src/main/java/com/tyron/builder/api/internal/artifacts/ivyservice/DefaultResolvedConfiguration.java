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
package com.tyron.builder.api.internal.artifacts.ivyservice;

import com.tyron.builder.api.artifacts.Dependency;
import com.tyron.builder.api.artifacts.LenientConfiguration;
import com.tyron.builder.api.artifacts.ResolveException;
import com.tyron.builder.api.artifacts.ResolvedArtifact;
import com.tyron.builder.api.artifacts.ResolvedConfiguration;
import com.tyron.builder.api.artifacts.ResolvedDependency;
import com.tyron.builder.api.specs.Spec;
import com.tyron.builder.api.specs.Specs;
import com.tyron.builder.util.Predicates;

import java.io.File;
import java.util.Collection;
import java.util.Set;
import java.util.function.Predicate;

public class DefaultResolvedConfiguration implements ResolvedConfiguration {
    private final DefaultLenientConfiguration configuration;

    public DefaultResolvedConfiguration(DefaultLenientConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public boolean hasError() {
        return configuration.hasError();
    }

    @Override
    public void rethrowFailure() throws ResolveException {
        configuration.rethrowFailure();
    }

    @Override
    public LenientConfiguration getLenientConfiguration() {
        return configuration;
    }

    @Override
    public Set<File> getFiles() throws ResolveException {
        return getFiles(Predicates.satisfyAll());
    }

    @Override
    public Set<File> getFiles(final Predicate<? super Dependency> dependencySpec) throws ResolveException {
        ResolvedFilesCollectingVisitor visitor = new ResolvedFilesCollectingVisitor();
        configuration.select(dependencySpec).visitArtifacts(visitor, false);
        Collection<Throwable> failures = visitor.getFailures();
        if (!failures.isEmpty()) {
            throw new DefaultLenientConfiguration.ArtifactResolveException("files", configuration.getConfiguration().getIdentityPath().toString(), configuration.getConfiguration().getDisplayName(), failures);
        }
        return visitor.getFiles();
    }

    @Override
    public Set<ResolvedDependency> getFirstLevelModuleDependencies() throws ResolveException {
        rethrowFailure();
        return configuration.getFirstLevelModuleDependencies();
    }

    @Override
    public Set<ResolvedDependency> getFirstLevelModuleDependencies(Predicate<? super Dependency> dependencySpec) throws ResolveException {
        rethrowFailure();
        return configuration.getFirstLevelModuleDependencies(dependencySpec);
    }

    @Override
    public Set<ResolvedArtifact> getResolvedArtifacts() throws ResolveException {
        rethrowFailure();
        ArtifactCollectingVisitor visitor = new ArtifactCollectingVisitor();
        configuration.select().visitArtifacts(visitor, false);
        return visitor.getArtifacts();
    }
}
