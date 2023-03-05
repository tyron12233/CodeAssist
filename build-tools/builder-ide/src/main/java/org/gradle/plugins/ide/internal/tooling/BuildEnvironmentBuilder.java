package org.gradle.plugins.ide.internal.tooling;

import org.gradle.api.Project;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.process.internal.CurrentProcess;
import org.gradle.tooling.internal.build.DefaultBuildEnvironment;
import org.gradle.tooling.internal.gradle.DefaultBuildIdentifier;
import org.gradle.tooling.provider.model.ToolingModelBuilder;

import java.io.File;
import java.util.List;

/**
 * Builds the GradleProject that contains the project hierarchy and task information
 */
public class BuildEnvironmentBuilder implements ToolingModelBuilder {
    private final FileCollectionFactory fileCollectionFactory;

    public BuildEnvironmentBuilder(FileCollectionFactory fileCollectionFactory) {
        this.fileCollectionFactory = fileCollectionFactory;
    }

    @Override
    public boolean canBuild(String modelName) {
        return modelName.equals("org.gradle.tooling.model.build.BuildEnvironment");
    }

    @Override
    public Object buildAll(String modelName, Project target) {
        File gradleUserHomeDir = target.getGradle().getGradleUserHomeDir();
        String gradleVersion = target.getGradle().getGradleVersion();

        CurrentProcess currentProcess = new CurrentProcess(fileCollectionFactory);
        File javaHome = currentProcess.getJvm().getJavaHome();
        List<String> jvmArgs = currentProcess.getJvmOptions().getAllImmutableJvmArgs();

        DefaultBuildIdentifier buildIdentifier = new DefaultBuildIdentifier(target.getRootDir());

        return new DefaultBuildEnvironment(buildIdentifier, gradleUserHomeDir, gradleVersion, javaHome, jvmArgs);
    }
}
