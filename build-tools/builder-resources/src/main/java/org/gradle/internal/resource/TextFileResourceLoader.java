package org.gradle.internal.resource;

import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

import javax.annotation.Nullable;
import java.io.File;

@ServiceScope(Scopes.Build.class)
public interface TextFileResourceLoader {
    TextResource loadFile(String description, @Nullable File sourceFile);
}
