package com.tyron.builder.api.internal.project;

import com.tyron.builder.api.initialization.ProjectDescriptor;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.groovy.scripts.TextResourceScriptSource;
import com.tyron.builder.initialization.DefaultProjectDescriptor;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.resource.TextFileResourceLoader;
import com.tyron.builder.util.internal.NameValidator;

import org.jetbrains.annotations.Nullable;

import java.io.File;

public class ProjectFactory implements IProjectFactory {

    private final Instantiator instantiator;
    private final TextFileResourceLoader textFileResourceLoader;

    public ProjectFactory(Instantiator instantiator, TextFileResourceLoader textFileResourceLoader) {
        this.instantiator = instantiator;
        this.textFileResourceLoader = textFileResourceLoader;
    }

    @Override
    public ProjectInternal createProject(GradleInternal gradle,
                                         ProjectDescriptor descriptor,
                                         ProjectStateUnk owner,
                                         @Nullable ProjectInternal parent,
                                         ClassLoaderScope selfClassLoaderScope,
                                         ClassLoaderScope baseClassLoaderScope
    ) {
        File buildFile = descriptor.getBuildFile();
        TextResourceScriptSource source = new TextResourceScriptSource(
                textFileResourceLoader.loadFile("build file", buildFile));
        DefaultProject project = instantiator.newInstance(
                DefaultProject.class,
                descriptor.getName(),
                parent,
                descriptor.getProjectDir(),
                buildFile,
                source,
                gradle,
                owner,
                gradle.getServiceRegistryFactory(),
                selfClassLoaderScope,
                baseClassLoaderScope
        );
        project.beforeEvaluate(p -> {
            NameValidator.validate(project.getName(), "project name", DefaultProjectDescriptor.INVALID_NAME_IN_INCLUDE_HINT);
        });
        gradle.getProjectRegistry().addProject(project);
        return project;
    }
}
