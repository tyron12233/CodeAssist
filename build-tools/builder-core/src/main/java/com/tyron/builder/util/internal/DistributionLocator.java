package com.tyron.builder.util.internal;

import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.util.GradleVersion;

import java.net.URI;
import java.net.URISyntaxException;

public class DistributionLocator {
    private static final String RELEASE_REPOSITORY = "https://services.gradle.org/distributions";
    private static final String SNAPSHOT_REPOSITORY = "https://services.gradle.org/distributions-snapshots";

    public URI getDistributionFor(GradleVersion version) {
        return getDistributionFor(version, "bin");
    }

    public URI getDistributionFor(GradleVersion version, String type) {
        return getDistribution(getDistributionRepository(version), version, "gradle", type);
    }

    private String getDistributionRepository(GradleVersion version) {
        if (version.isSnapshot()) {
            return SNAPSHOT_REPOSITORY;
        } else {
            return RELEASE_REPOSITORY;
        }
    }

    private URI getDistribution(String repositoryUrl, GradleVersion version, String archiveName,
                                   String archiveClassifier) {
        try {
            return new URI(repositoryUrl + "/" + archiveName + "-" + version.getVersion() + "-" + archiveClassifier + ".zip");
        } catch (URISyntaxException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }
}
