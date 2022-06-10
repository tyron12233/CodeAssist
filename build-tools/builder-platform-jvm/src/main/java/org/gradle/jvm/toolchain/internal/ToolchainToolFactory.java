package org.gradle.jvm.toolchain.internal;

import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

@ServiceScope(Scopes.Project.class)
public interface ToolchainToolFactory {

    <T> T create(Class<T> toolType, JavaToolchain javaToolchain);

}
