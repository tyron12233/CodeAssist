package com.tyron.builder.api.internal.reflect.service.scopes;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.file.ConfigurableFileTree;
import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.internal.file.ConfigurableFileCollection;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.api.internal.file.FileTreeInternal;
import com.tyron.builder.api.internal.file.PathToFileResolver;
import com.tyron.builder.api.internal.file.collections.MinimalFileSet;
import com.tyron.builder.api.internal.file.collections.MinimalFileTree;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.reflect.service.DefaultServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.tasks.DefaultTaskContainer;
import com.tyron.builder.api.internal.tasks.TaskContainerInternal;
import com.tyron.builder.api.tasks.TaskDependency;

import java.io.File;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;

public class ProjectScopeServices extends DefaultServiceRegistry {

    private final ProjectInternal project;

    public ProjectScopeServices(final ServiceRegistry parent, final ProjectInternal project) {
        super(parent);
        this.project = project;
//        this.loggingManagerInternalFactory = loggingManagerInternalFactory;
        register(registration -> {
            registration.add(ProjectInternal.class, project);
//            parent.get(DependencyManagementServices.class).addDslServices(registration, project);
//            for (PluginServiceRegistry pluginServiceRegistry : parent.getAll(PluginServiceRegistry.class)) {
//                pluginServiceRegistry.registerProjectServices(registration);
//            }
        });
        addProvider(new WorkerSharedProjectScopeServices(project.getProjectDir()));
    }

    protected TaskContainerInternal createTaskContainerInternal() {
        return new DefaultTaskContainer(project);
    }

    FileCollectionFactory createFileCollectionFactory() {
        return new FileCollectionFactory() {
            @Override
            public FileCollectionFactory withResolver(PathToFileResolver fileResolver) {
                return null;
            }

            @Override
            public FileCollectionInternal create(MinimalFileSet contents) {
                return null;
            }

            @Override
            public FileCollectionInternal create(TaskDependency builtBy, MinimalFileSet contents) {
                return null;
            }

            @Override
            public FileCollectionInternal empty(String displayName) {
                return null;
            }

            @Override
            public FileCollectionInternal empty() {
                return null;
            }

            @Override
            public FileCollectionInternal fixed(File... files) {
                return null;
            }

            @Override
            public FileCollectionInternal fixed(Collection<File> files) {
                return null;
            }

            @Override
            public FileCollectionInternal fixed(String displayName, File... files) {
                return null;
            }

            @Override
            public FileCollectionInternal fixed(String displayName, Collection<File> files) {
                return null;
            }

            @Override
            public FileCollectionInternal resolving(String displayName, Object sources) {
                return null;
            }

            @Override
            public FileCollectionInternal resolvingLeniently(String displayName, Object sources) {
                return null;
            }

            @Override
            public FileCollectionInternal resolving(Object sources) {
                return null;
            }

            @Override
            public FileCollectionInternal resolvingLeniently(Object sources) {
                return null;
            }

            @Override
            public ConfigurableFileCollection configurableFiles(String displayName) {
                return null;
            }

            @Override
            public ConfigurableFileCollection configurableFiles() {
                return null;
            }

            @Override
            public ConfigurableFileTree fileTree() {
                return null;
            }

            @Override
            public FileTreeInternal generated(Factory<File> tmpDir,
                                              String fileName,
                                              Action<File> fileGenerationListener,
                                              Action<OutputStream> contentGenerator) {
                return null;
            }

            @Override
            public FileTreeInternal treeOf(List<? extends FileTreeInternal> fileTrees) {
                return null;
            }

            @Override
            public FileTreeInternal treeOf(MinimalFileTree tree) {
                return null;
            }
        };
    }

}
