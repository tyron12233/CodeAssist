package org.gradle.launcher;

import org.gradle.initialization.BuildCancellationToken;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.scopes.BuildScopeServices;
import org.gradle.configuration.GradleLauncherMetaData;
import org.gradle.initialization.BuildClientMetaData;
import org.gradle.initialization.DefaultBuildCancellationToken;
import org.gradle.internal.build.BuildModelControllerServices;

import java.io.File;

public class TestBuildScopeServices extends BuildScopeServices {
    private final File homeDir;

    public TestBuildScopeServices(ServiceRegistry parent, File homeDir, BuildModelControllerServices.Supplier supplier) {
        super(parent, supplier);
        this.homeDir = homeDir;
    }

    protected BuildCancellationToken createBuildCancellationToken() {
        return new DefaultBuildCancellationToken();
    }

    protected BuildClientMetaData createClientMetaData() {
        return new GradleLauncherMetaData();
    }
}
