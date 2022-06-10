package org.gradle.initialization;


import org.gradle.api.internal.project.DefaultProjectRegistry;
import org.gradle.util.Path;

public class DefaultProjectDescriptorRegistry extends DefaultProjectRegistry<DefaultProjectDescriptor> implements ProjectDescriptorRegistry {

    @Override
    public void changeDescriptorPath(Path oldPath, Path newPath) {
        DefaultProjectDescriptor projectDescriptor = removeProject(oldPath.toString());
        projectDescriptor.setPath(newPath);
        addProject(projectDescriptor);
    }
}