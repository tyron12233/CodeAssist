package com.tyron.builder.initialization;


import com.tyron.builder.api.internal.project.DefaultProjectRegistry;
import com.tyron.builder.util.Path;

public class DefaultProjectDescriptorRegistry extends DefaultProjectRegistry<DefaultProjectDescriptor> implements ProjectDescriptorRegistry {

    @Override
    public void changeDescriptorPath(Path oldPath, Path newPath) {
        DefaultProjectDescriptor projectDescriptor = removeProject(oldPath.toString());
        projectDescriptor.setPath(newPath);
        addProject(projectDescriptor);
    }
}