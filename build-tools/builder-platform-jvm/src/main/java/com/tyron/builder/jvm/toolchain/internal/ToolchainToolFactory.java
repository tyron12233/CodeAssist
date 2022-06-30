package com.tyron.builder.jvm.toolchain.internal;

import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

@ServiceScope(Scopes.Project.class)
public interface ToolchainToolFactory {

    <T> T create(Class<T> toolType, JavaToolchain javaToolchain);

}
