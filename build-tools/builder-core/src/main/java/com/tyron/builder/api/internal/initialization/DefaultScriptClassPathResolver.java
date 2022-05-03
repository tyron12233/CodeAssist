package com.tyron.builder.api.internal.initialization;

import com.tyron.builder.api.artifacts.ArtifactView;
import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.DependencyFactory;
import com.tyron.builder.internal.classpath.ClassPath;
import com.tyron.builder.internal.classpath.DefaultClassPath;
import com.tyron.builder.internal.component.local.model.OpaqueComponentIdentifier;

import java.util.List;

public class DefaultScriptClassPathResolver implements ScriptClassPathResolver {
    private final List<ScriptClassPathInitializer> initializers;

    public DefaultScriptClassPathResolver(List<ScriptClassPathInitializer> initializers) {
        this.initializers = initializers;
    }

    @Override
    public ClassPath resolveClassPath(Configuration classpathConfiguration) {
        if (classpathConfiguration == null) {
            return ClassPath.EMPTY;
        }
        for (ScriptClassPathInitializer initializer : initializers) {
            initializer.execute(classpathConfiguration);
        }
        ArtifactView view = classpathConfiguration.getIncoming().artifactView(config -> {
            config.componentFilter(componentId -> {
                if (componentId instanceof OpaqueComponentIdentifier) {
                    DependencyFactory.ClassPathNotation classPathNotation = ((OpaqueComponentIdentifier) componentId).getClassPathNotation();
                    return classPathNotation != DependencyFactory.ClassPathNotation.GRADLE_API && classPathNotation != DependencyFactory.ClassPathNotation.LOCAL_GROOVY;
                }
                return true;
            });
        });
        return DefaultClassPath.of(view.getFiles());
    }
}
