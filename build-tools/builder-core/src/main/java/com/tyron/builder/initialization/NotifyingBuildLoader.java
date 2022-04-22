package com.tyron.builder.initialization;

import com.google.common.collect.ImmutableSortedSet;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.internal.operations.BuildOperationContext;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.CallableBuildOperation;
import com.tyron.builder.internal.operations.RunnableBuildOperation;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.BuildProject;

import java.util.Comparator;
import java.util.Set;

public class NotifyingBuildLoader implements BuildLoader {

    private static final NotifyProjectsLoadedBuildOperationType.Result PROJECTS_LOADED_OP_RESULT = new NotifyProjectsLoadedBuildOperationType.Result() {
    };

    private final BuildLoader buildLoader;
    private final BuildOperationExecutor buildOperationExecutor;

    public NotifyingBuildLoader(BuildLoader buildLoader, BuildOperationExecutor buildOperationExecutor) {
        this.buildLoader = buildLoader;
        this.buildOperationExecutor = buildOperationExecutor;
    }

    @Override
    public void load(final SettingsInternal settings, final GradleInternal gradle) {
        final String buildPath = gradle.getIdentityPath().toString();
        buildOperationExecutor.call(new CallableBuildOperation<Void>() {
            @Override
            public BuildOperationDescriptor.Builder description() {
                //noinspection Convert2Lambda
                return BuildOperationDescriptor
                        .displayName("Load projects")
                        .progressDisplayName("Loading projects")
                        .details(new LoadProjectsBuildOperationType.Details() {
                            @Override
                            public String getBuildPath() {
                                return buildPath;
                            }
                        });
            }

            @Override
            public Void call(BuildOperationContext context) {
                buildLoader.load(settings, gradle);
                context.setResult(createOperationResult(gradle, buildPath));
                return null;
            }
        });
        buildOperationExecutor.run(new RunnableBuildOperation() {
            @Override
            public void run(BuildOperationContext context) {
                gradle.getBuildListenerBroadcaster().projectsLoaded(gradle);
                context.setResult(PROJECTS_LOADED_OP_RESULT);
            }

            @Override
            public BuildOperationDescriptor.Builder description() {
                //noinspection Convert2Lambda
                return BuildOperationDescriptor
                        .displayName(gradle.contextualize("Notify projectsLoaded listeners"))
                        .details(new NotifyProjectsLoadedBuildOperationType.Details() {
                            @Override
                            public String getBuildPath() {
                                return buildPath;
                            }
                        });
            }
        });
    }

    private BuildStructureOperationResult createOperationResult(GradleInternal gradle, String buildPath) {
        LoadProjectsBuildOperationType.Result.Project rootProject = convert(gradle.getRootProject());
        return new BuildStructureOperationResult(rootProject, buildPath);
    }

    private LoadProjectsBuildOperationType.Result.Project convert(BuildProject project) {
        return new BuildStructureOperationProject(
                project.getName(),
                project.getPath(),
                ((ProjectInternal) project).getIdentityPath().toString(),
                project.getProjectDir().getAbsolutePath(),
                project.getBuildFile().getAbsolutePath(),
                convert(project.getChildProjects().values()));
    }

    private Set<LoadProjectsBuildOperationType.Result.Project> convert(Iterable<BuildProject> children) {
        ImmutableSortedSet.Builder<LoadProjectsBuildOperationType.Result.Project> builder = new ImmutableSortedSet.Builder<>(PROJECT_COMPARATOR);
        for (BuildProject child : children) {
            builder.add(convert(child));
        }
        return builder.build();
    }

    private static class BuildStructureOperationResult implements LoadProjectsBuildOperationType.Result {
        private final Project rootProject;
        private final String buildPath;

        public BuildStructureOperationResult(Project rootProject, String buildPath) {
            this.rootProject = rootProject;
            this.buildPath = buildPath;
        }

        @Override
        public Project getRootProject() {
            return rootProject;
        }

        @Override
        public String getBuildPath() {
            return buildPath;
        }
    }

    private static class BuildStructureOperationProject implements LoadProjectsBuildOperationType.Result.Project {
        private final String name;
        private final String path;
        private final String identityPath;
        private final String projectDir;
        private final String buildFile;
        private final Set<LoadProjectsBuildOperationType.Result.Project> children;

        public BuildStructureOperationProject(String name, String path, String identityPath, String projectDir, String buildFile, Set<LoadProjectsBuildOperationType.Result.Project> children) {
            this.name = name;
            this.path = path;
            this.identityPath = identityPath;
            this.projectDir = projectDir;
            this.buildFile = buildFile;
            this.children = children;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public String getIdentityPath() {
            return identityPath;
        }

        @Override
        public String getProjectDir() {
            return projectDir;
        }

        @Override
        public String getBuildFile() {
            return buildFile;
        }

        @Override
        public Set<LoadProjectsBuildOperationType.Result.Project> getChildren() {
            return children;
        }
    }

    private static final Comparator<LoadProjectsBuildOperationType.Result.Project> PROJECT_COMPARATOR =
            (o1, o2) -> o1.getName().compareTo(o2.getName());
}
