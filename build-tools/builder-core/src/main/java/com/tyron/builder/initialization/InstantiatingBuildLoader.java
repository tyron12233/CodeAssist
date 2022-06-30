package com.tyron.builder.initialization;

import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.ProjectStateUnk;
import com.tyron.builder.internal.build.BuildState;

public class InstantiatingBuildLoader implements BuildLoader {
    @Override
    public void load(SettingsInternal settings, GradleInternal gradle) {
        createProjects(gradle, settings.getProjectRegistry().getRootProject());
        attachDefaultProject(gradle, settings.getDefaultProject());
    }

    private void attachDefaultProject(GradleInternal gradle, DefaultProjectDescriptor defaultProjectDescriptor) {
        ProjectStateUnk defaultProject = gradle.getOwner().getProjects().getProject(defaultProjectDescriptor.path());
        gradle.setDefaultProject(defaultProject.getMutableModel());
    }

    private void createProjects(GradleInternal gradle, DefaultProjectDescriptor rootProjectDescriptor) {
        ClassLoaderScope baseProjectClassLoaderScope = gradle.baseProjectClassLoaderScope();
        ClassLoaderScope rootProjectClassLoaderScope = baseProjectClassLoaderScope.createChild("root-project[" + gradle.getIdentityPath() + "]");

        ProjectStateUnk projectState = gradle.getOwner().getProjects().getProject(rootProjectDescriptor.path());
        projectState.createMutableModel(rootProjectClassLoaderScope, baseProjectClassLoaderScope);
        ProjectInternal rootProject = projectState.getMutableModel();
        gradle.setRootProject(rootProject);

        createChildProjectsRecursively(gradle.getOwner(), rootProjectDescriptor, rootProjectClassLoaderScope, baseProjectClassLoaderScope);
    }

    private void createChildProjectsRecursively(BuildState owner, DefaultProjectDescriptor parentProjectDescriptor, ClassLoaderScope parentProjectClassLoaderScope, ClassLoaderScope baseProjectClassLoaderScope) {
        for (DefaultProjectDescriptor childProjectDescriptor : parentProjectDescriptor.children()) {
            ClassLoaderScope childProjectClassLoaderScope = parentProjectClassLoaderScope.createChild("project-" + childProjectDescriptor.getName());
            ProjectStateUnk projectState = owner.getProjects().getProject(childProjectDescriptor.path());
            projectState.createMutableModel(childProjectClassLoaderScope, baseProjectClassLoaderScope);
            createChildProjectsRecursively(owner, childProjectDescriptor, parentProjectClassLoaderScope, baseProjectClassLoaderScope);
        }
    }
}

