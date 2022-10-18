package org.gradle.api.internal.initialization;

import org.gradle.api.artifacts.ArtifactView;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.internal.artifacts.dsl.dependencies.DependencyFactoryInternal;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.classpath.DefaultClassPath;
import org.gradle.internal.component.local.model.OpaqueComponentIdentifier;

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
                    DependencyFactoryInternal.ClassPathNotation classPathNotation = ((OpaqueComponentIdentifier) componentId).getClassPathNotation();
                    return classPathNotation != DependencyFactoryInternal.ClassPathNotation.GRADLE_API && classPathNotation != DependencyFactoryInternal.ClassPathNotation.LOCAL_GROOVY;
                }
                return true;
            });
        });
        return DefaultClassPath.of(view.getFiles());
    }
}
