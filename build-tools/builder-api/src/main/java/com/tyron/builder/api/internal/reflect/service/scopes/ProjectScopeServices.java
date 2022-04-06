package com.tyron.builder.api.internal.reflect.service.scopes;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.file.ConfigurableFileTree;
import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.internal.file.ConfigurableFileCollection;
import com.tyron.builder.api.internal.file.DefaultFileCollectionFactory;
import com.tyron.builder.api.internal.file.DefaultFileLookup;
import com.tyron.builder.api.internal.file.DefaultFilePropertyFactory;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.api.internal.file.FileLookup;
import com.tyron.builder.api.internal.file.FilePropertyFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.file.FileTreeInternal;
import com.tyron.builder.api.internal.file.PathToFileResolver;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTree;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTreeFactory;
import com.tyron.builder.api.internal.file.collections.MinimalFileSet;
import com.tyron.builder.api.internal.file.collections.MinimalFileTree;
import com.tyron.builder.api.internal.nativeintegration.FileSystem;
import com.tyron.builder.api.internal.operations.BuildOperationExecutor;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.provider.PropertyHost;
import com.tyron.builder.api.internal.reflect.service.DefaultServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.tasks.DefaultTaskContainer;
import com.tyron.builder.api.internal.tasks.DefaultTaskDependencyFactory;
import com.tyron.builder.api.internal.tasks.TaskContainerInternal;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.model.internal.DefaultObjectFactory;
import com.tyron.builder.api.tasks.TaskDependency;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.api.tasks.util.internal.PatternSets;
import com.tyron.builder.api.tasks.util.internal.PatternSpecFactory;

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

    protected TaskContainerInternal createTaskContainerInternal(
            BuildOperationExecutor buildOperationExecutor
    ) {
        return new DefaultTaskContainer(project, buildOperationExecutor);
    }

    FileLookup createFileLookup() {
        return new DefaultFileLookup();
    }

    FileResolver createFileResolver(FileLookup lookup) {
        return lookup.getFileResolver();
    }

    PropertyHost createPropertyHost() {
        return PropertyHost.NO_OP;
    }

    FileCollectionFactory createFileCollectionFactory(FileLookup fileResolver, Factory<PatternSet> patternSetFactory, DirectoryFileTreeFactory directoryFileTreeFactory, PropertyHost propertyHost, FileSystem fileSystem) {
        return new DefaultFileCollectionFactory(fileResolver.getFileResolver(), DefaultTaskDependencyFactory.withNoAssociatedProject(), directoryFileTreeFactory, patternSetFactory, propertyHost, fileSystem);
    }

    FilePropertyFactory createFilePropertyFactory(
            PropertyHost propertyHost,
            FileResolver fileResolver,
            FileCollectionFactory fileCollectionFactory
    ) {
        return new DefaultFilePropertyFactory(propertyHost, fileResolver, fileCollectionFactory);
    }

    ObjectFactory createObjectFactory(
            FileCollectionFactory fileCollectionFactory,
            FilePropertyFactory filePropertyFactory
    ) {
        return new DefaultObjectFactory(fileCollectionFactory, filePropertyFactory);
    }

    PatternSpecFactory createPatternSpecFactory() {
        return PatternSpecFactory.INSTANCE;
    }

    DirectoryFileTreeFactory createDirectoryTreeFileFactory(FileSystem fileSystem) {
        return new DirectoryFileTreeFactory() {
            @Override
            public DirectoryFileTree create(File directory) {
                return new DirectoryFileTree(directory, null, fileSystem);
            }

            @Override
            public DirectoryFileTree create(File directory, PatternSet patternSet) {
                return new DirectoryFileTree(directory, patternSet, fileSystem);
            }
        };
    }

    protected Factory<PatternSet> createPatternSetFactory(final PatternSpecFactory patternSpecFactory) {
        return PatternSets.getPatternSetFactory(patternSpecFactory);
    }

}
