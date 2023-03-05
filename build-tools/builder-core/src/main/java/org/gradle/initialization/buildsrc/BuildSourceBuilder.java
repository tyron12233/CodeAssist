package org.gradle.initialization.buildsrc;

import org.gradle.api.internal.GradleInternal;
import org.gradle.cache.FileLock;
import org.gradle.cache.FileLockManager;
import org.gradle.cache.LockOptions;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.build.BuildStateRegistry;
import org.gradle.internal.build.PublicBuildPath;
import org.gradle.internal.build.StandAloneNestedBuild;
import org.gradle.internal.classpath.CachedClasspathTransformer;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.CallableBuildOperation;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

import java.io.File;

import static org.gradle.cache.internal.filelock.LockOptionsBuilder.mode;

@ServiceScope(Scopes.Build.class)
public class BuildSourceBuilder {
    private static final BuildBuildSrcBuildOperationType.Result BUILD_BUILDSRC_RESULT = new BuildBuildSrcBuildOperationType.Result() {
    };

    private final BuildState currentBuild;
    private final FileLockManager fileLockManager;
    private final BuildOperationExecutor buildOperationExecutor;
    private final CachedClasspathTransformer cachedClasspathTransformer;
    private final BuildSrcBuildListenerFactory buildSrcBuildListenerFactory;
    private final BuildStateRegistry buildRegistry;
    private final PublicBuildPath publicBuildPath;

    public BuildSourceBuilder(BuildState currentBuild, FileLockManager fileLockManager, BuildOperationExecutor buildOperationExecutor, CachedClasspathTransformer cachedClasspathTransformer, BuildSrcBuildListenerFactory buildSrcBuildListenerFactory, BuildStateRegistry buildRegistry, PublicBuildPath publicBuildPath) {
        this.currentBuild = currentBuild;
        this.fileLockManager = fileLockManager;
        this.buildOperationExecutor = buildOperationExecutor;
        this.cachedClasspathTransformer = cachedClasspathTransformer;
        this.buildSrcBuildListenerFactory = buildSrcBuildListenerFactory;
        this.buildRegistry = buildRegistry;
        this.publicBuildPath = publicBuildPath;
    }

    public ClassPath buildAndGetClassPath(GradleInternal gradle) {
        return createBuildSourceClasspath();
    }

    private ClassPath createBuildSourceClasspath() {
        StandAloneNestedBuild buildSrcBuild = buildRegistry.getBuildSrcNestedBuild(currentBuild);
        if (buildSrcBuild == null) {
            return ClassPath.EMPTY;
        }

        return buildOperationExecutor.call(new CallableBuildOperation<ClassPath>() {
            @Override
            public ClassPath call(BuildOperationContext context) {
                ClassPath classPath = buildBuildSrc(buildSrcBuild);
                context.setResult(BUILD_BUILDSRC_RESULT);
                return classPath;
            }

            @Override
            public BuildOperationDescriptor.Builder description() {
                //noinspection Convert2Lambda
                return BuildOperationDescriptor.displayName("Build buildSrc").
                    progressDisplayName("Building buildSrc").
                    details(
                        new BuildBuildSrcBuildOperationType.Details() {
                            @Override
                            public String getBuildPath() {
                                return publicBuildPath.getBuildPath().toString();
                            }
                        }
                    );
            }
        });
    }

    @SuppressWarnings("try")
    private ClassPath buildBuildSrc(StandAloneNestedBuild buildSrcBuild) {
        return buildSrcBuild.run(buildController -> {
            try (FileLock ignored = buildSrcBuildLockFor(buildSrcBuild)) {
                return new BuildSrcUpdateFactory(buildController, buildSrcBuildListenerFactory, cachedClasspathTransformer).create();
            }
        });
    }

    private FileLock buildSrcBuildLockFor(StandAloneNestedBuild build) {
        return fileLockManager.lock(
            new File(build.getBuildRootDir(), ".gradle/noVersion/buildSrc"),
            LOCK_OPTIONS,
            "buildSrc build lock"
        );
    }

    private static final LockOptions LOCK_OPTIONS = mode(FileLockManager.LockMode.Exclusive).useCrossVersionImplementation();
}
