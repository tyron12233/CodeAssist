package com.tyron.builder.api.internal.artifacts.configurations.dynamicversion;

import com.tyron.builder.api.artifacts.ArtifactIdentifier;
import com.tyron.builder.api.artifacts.ModuleIdentifier;
import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.ResolvedModuleVersion;
import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;

import javax.annotation.Nullable;
import java.io.File;
import java.time.Duration;
import java.util.Set;

public interface CachePolicy {
    Expiry versionListExpiry(ModuleIdentifier selector, Set<ModuleVersionIdentifier> moduleVersions, Duration age);

    Expiry missingModuleExpiry(ModuleComponentIdentifier component, Duration age);

    Expiry moduleExpiry(ModuleComponentIdentifier component, ResolvedModuleVersion resolvedModuleVersion, Duration age);

    Expiry moduleExpiry(ResolvedModuleVersion resolvedModuleVersion, Duration age, boolean changing);

    Expiry changingModuleExpiry(ModuleComponentIdentifier component, ResolvedModuleVersion resolvedModuleVersion, Duration age);

    Expiry moduleArtifactsExpiry(ModuleVersionIdentifier moduleVersionId, Set<ArtifactIdentifier> artifacts, Duration age, boolean belongsToChangingModule, boolean moduleDescriptorInSync);

    Expiry artifactExpiry(ArtifactIdentifier artifactIdentifier, @Nullable File cachedArtifactFile, Duration age, boolean belongsToChangingModule, boolean moduleDescriptorInSync);

    void setOffline();

    void setRefreshDependencies();

}
