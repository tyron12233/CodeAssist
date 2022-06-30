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
package com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve;

import com.tyron.builder.api.internal.artifacts.configurations.dynamicversion.CachePolicy;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.verification.ChecksumAndSignatureVerificationOverride;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.verification.DependencyVerificationOverride;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.verification.writer.WriteDependencyVerificationFile;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolutionstrategy.ExternalResourceCachePolicy;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.MetadataFetchingCost;
import com.tyron.builder.api.internal.artifacts.verification.signatures.BuildTreeDefinedKeys;
import com.tyron.builder.api.internal.artifacts.verification.signatures.SignatureVerificationServiceFactory;
import com.tyron.builder.internal.resolve.ArtifactResolveException;
import com.tyron.builder.internal.resolve.ModuleVersionResolveException;

import com.tyron.builder.StartParameter;
import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;
import com.tyron.builder.api.artifacts.result.ResolvedArtifactResult;
import com.tyron.builder.api.artifacts.verification.DependencyVerificationMode;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.artifacts.configurations.ResolutionStrategyInternal;
import com.tyron.builder.api.internal.artifacts.verification.DependencyVerificationException;

import com.tyron.builder.api.internal.component.ArtifactType;
import com.tyron.builder.api.internal.properties.GradleProperties;
import com.tyron.builder.api.invocation.Gradle;
import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.Logging;
import com.tyron.builder.api.resources.ResourceException;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.component.external.model.ModuleDependencyMetadata;
import com.tyron.builder.internal.component.model.ComponentArtifactMetadata;
import com.tyron.builder.internal.component.model.ComponentOverrideMetadata;
import com.tyron.builder.internal.component.model.ComponentResolveMetadata;
import com.tyron.builder.internal.component.model.ConfigurationMetadata;
import com.tyron.builder.internal.component.model.ModuleSources;
import com.tyron.builder.internal.concurrent.CompositeStoppable;
import com.tyron.builder.internal.concurrent.Stoppable;
import com.tyron.builder.internal.hash.ChecksumService;
import com.tyron.builder.internal.lazy.Lazy;
import com.tyron.builder.internal.operations.BuildOperationExecutor;

import com.tyron.builder.internal.resolve.result.BuildableArtifactResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableArtifactSetResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableComponentArtifactsResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableModuleComponentMetaDataResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableModuleVersionListingResolveResult;
import com.tyron.builder.internal.resource.ExternalResource;
import com.tyron.builder.internal.resource.ExternalResourceName;
import com.tyron.builder.internal.resource.ReadableContent;
import com.tyron.builder.internal.resource.metadata.ExternalResourceMetaData;
import com.tyron.builder.internal.resource.transfer.ExternalResourceConnector;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;
import com.tyron.builder.util.internal.BuildCommencedTimeProvider;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.List;

@ServiceScope(Scopes.BuildTree.class)
public class StartParameterResolutionOverride {
    private final StartParameter startParameter;
    private final File gradleDir;
    private final Lazy<BuildTreeDefinedKeys> keyRing;

    public StartParameterResolutionOverride(StartParameter startParameter, File gradleDir) {
        this.startParameter = startParameter;
        this.gradleDir = gradleDir;
        this.keyRing = Lazy.locking().of(() -> {
            File keyringsFile = DependencyVerificationOverride.keyringsFile(gradleDir);
            return new BuildTreeDefinedKeys(keyringsFile);
        });
    }

    public void applyToCachePolicy(CachePolicy cachePolicy) {
        if (startParameter.isOffline()) {
            cachePolicy.setOffline();
        } else if (startParameter.isRefreshDependencies()) {
            cachePolicy.setRefreshDependencies();
        }
    }

    public ModuleComponentRepository overrideModuleVersionRepository(ModuleComponentRepository original) {
        if (startParameter.isOffline()) {
            return new OfflineModuleComponentRepository(original);
        }
        return original;
    }

    public DependencyVerificationOverride dependencyVerificationOverride(BuildOperationExecutor buildOperationExecutor,
                                                                         ChecksumService checksumService,
                                                                         SignatureVerificationServiceFactory signatureVerificationServiceFactory,
                                                                         DocumentationRegistry documentationRegistry,
                                                                         BuildCommencedTimeProvider timeProvider,
                                                                         Factory<GradleProperties> gradlePropertiesFactory) {
        List<String> checksums = startParameter.getWriteDependencyVerifications();
        if (!checksums.isEmpty()) {
            File verificationsFile = DependencyVerificationOverride.dependencyVerificationsFile(gradleDir);
            return DisablingVerificationOverride.of(
                new WriteDependencyVerificationFile(verificationsFile, keyRing.get(), buildOperationExecutor, checksums, checksumService, signatureVerificationServiceFactory, startParameter.isDryRun(), startParameter.isExportKeys())
            );
        } else {
            File verificationsFile = DependencyVerificationOverride.dependencyVerificationsFile(gradleDir);
            if (verificationsFile.exists()) {
                if (startParameter.getDependencyVerificationMode() == DependencyVerificationMode.OFF) {
                    return DependencyVerificationOverride.NO_VERIFICATION;
                }
                try {
                    File sessionReportDir = computeReportDirectory(timeProvider);
                    return DisablingVerificationOverride.of(
                        new ChecksumAndSignatureVerificationOverride(buildOperationExecutor, startParameter.getGradleUserHomeDir(), verificationsFile, keyRing.get(), checksumService, signatureVerificationServiceFactory, startParameter.getDependencyVerificationMode(), documentationRegistry, sessionReportDir, gradlePropertiesFactory)
                    );
                } catch (Exception e) {
                    return new FailureVerificationOverride(e);
                }
            }
        }
        return DependencyVerificationOverride.NO_VERIFICATION;
    }

    private File computeReportDirectory(BuildCommencedTimeProvider timeProvider) {
        // TODO: This is not quite correct: we're using the "root project" build directory
        // but technically speaking, this can be changed _after_ this service is created.
        // There's currently no good way to figure that out.
        File buildDir = new File(gradleDir.getParentFile(), "build");
        File reportsDirectory = new File(buildDir, "reports");
        File verifReportsDirectory = new File(reportsDirectory, "dependency-verification");
        return new File(verifReportsDirectory, "at-" + timeProvider.getCurrentTime());
    }

    private static class OfflineModuleComponentRepository extends BaseModuleComponentRepository {

        private final FailedRemoteAccess failedRemoteAccess = new FailedRemoteAccess();

        public OfflineModuleComponentRepository(ModuleComponentRepository original) {
            super(original);
        }

        @Override
        public ModuleComponentRepositoryAccess getRemoteAccess() {
            return failedRemoteAccess;
        }
    }

    private static class FailedRemoteAccess implements ModuleComponentRepositoryAccess {
        @Override
        public String toString() {
            return "offline remote";
        }

        @Override
        public void listModuleVersions(ModuleDependencyMetadata dependency, BuildableModuleVersionListingResolveResult result) {
            result.failed(new ModuleVersionResolveException(dependency.getSelector(), () -> String.format("No cached version listing for %s available for offline mode.", dependency.getSelector())));
        }

        @Override
        public void resolveComponentMetaData(ModuleComponentIdentifier moduleComponentIdentifier, ComponentOverrideMetadata requestMetaData, BuildableModuleComponentMetaDataResolveResult result) {
            result.failed(new ModuleVersionResolveException(moduleComponentIdentifier, () -> String.format("No cached version of %s available for offline mode.", moduleComponentIdentifier.getDisplayName())));
        }

        @Override
        public void resolveArtifactsWithType(ComponentResolveMetadata component, ArtifactType artifactType, BuildableArtifactSetResolveResult result) {
            result.failed(new ArtifactResolveException(component.getId(), "No cached version available for offline mode"));
        }

        @Override
        public void resolveArtifacts(ComponentResolveMetadata component, ConfigurationMetadata variant, BuildableComponentArtifactsResolveResult result) {
            result.failed(new ArtifactResolveException(component.getId(), "No cached version available for offline mode"));
        }

        @Override
        public void resolveArtifact(ComponentArtifactMetadata artifact, ModuleSources moduleSources, BuildableArtifactResolveResult result) {
            result.failed(new ArtifactResolveException(artifact.getId(), "No cached version available for offline mode"));
        }

        @Override
        public MetadataFetchingCost estimateMetadataFetchingCost(ModuleComponentIdentifier moduleComponentIdentifier) {
            return MetadataFetchingCost.CHEAP;
        }
    }

    public ExternalResourceCachePolicy overrideExternalResourceCachePolicy(ExternalResourceCachePolicy original) {
        if (startParameter.isOffline()) {
            return ageMillis -> false;
        }
        return original;
    }

    public ExternalResourceConnector overrideExternalResourceConnector(ExternalResourceConnector original) {
        if (startParameter.isOffline()) {
            return new OfflineExternalResourceConnector();
        }
        return original;
    }

    private static class OfflineExternalResourceConnector implements ExternalResourceConnector {
        @Nullable
        @Override
        public <T> T withContent(ExternalResourceName location, boolean revalidate, ExternalResource.ContentAndMetadataAction<T> action) throws ResourceException {
            throw offlineResource(location);
        }

        @Nullable
        @Override
        public ExternalResourceMetaData getMetaData(ExternalResourceName location, boolean revalidate) throws ResourceException {
            throw offlineResource(location);
        }

        @Nullable
        @Override
        public List<String> list(ExternalResourceName parent) throws ResourceException {
            throw offlineResource(parent);
        }

        @Override
        public void upload(ReadableContent resource, ExternalResourceName destination) throws IOException {
            throw new ResourceException(destination.getUri(), String.format("Cannot upload to '%s' in offline mode.", destination.getUri()));
        }

        private ResourceException offlineResource(ExternalResourceName source) {
            return new ResourceException(source.getUri(), String.format("No cached resource '%s' available for offline mode.", source.getUri()));
        }
    }

    private static class FailureVerificationOverride implements DependencyVerificationOverride {
        private final Exception error;

        private FailureVerificationOverride(Exception error) {
            this.error = error;
        }

        @Override
        public ModuleComponentRepository overrideDependencyVerification(ModuleComponentRepository original, String resolveContextName, ResolutionStrategyInternal resolutionStrategy) {
            throw new DependencyVerificationException("Dependency verification cannot be performed", error);
        }
    }

    public static class DisablingVerificationOverride implements DependencyVerificationOverride, Stoppable {
        private final static Logger LOGGER = Logging.getLogger(DependencyVerificationOverride.class);

        private final DependencyVerificationOverride delegate;

        public static DisablingVerificationOverride of(DependencyVerificationOverride delegate) {
            return new DisablingVerificationOverride(delegate);
        }

        private DisablingVerificationOverride(DependencyVerificationOverride delegate) {
            this.delegate = delegate;
        }

        @Override
        public ModuleComponentRepository overrideDependencyVerification(ModuleComponentRepository original, String resolveContextName, ResolutionStrategyInternal resolutionStrategy) {
            if (resolutionStrategy.isDependencyVerificationEnabled()) {
                return delegate.overrideDependencyVerification(original, resolveContextName, resolutionStrategy);
            } else {
                LOGGER.warn("Dependency verification has been disabled for configuration " + resolveContextName);
                return original;
            }
        }

        @Override
        public void buildFinished(Gradle gradle) {
            delegate.buildFinished(gradle);
        }

        @Override
        public void artifactsAccessed(String displayName) {
            delegate.artifactsAccessed(displayName);
        }

        @Override
        public ResolvedArtifactResult verifiedArtifact(ResolvedArtifactResult artifact) {
            return delegate.verifiedArtifact(artifact);
        }

        @Override
        public void stop() {
            CompositeStoppable.stoppable(delegate).stop();
        }
    }
}
