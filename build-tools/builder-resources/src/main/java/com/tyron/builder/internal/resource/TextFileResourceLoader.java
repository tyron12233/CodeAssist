package com.tyron.builder.internal.resource;

import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

import javax.annotation.Nullable;
import java.io.File;

@ServiceScope(Scopes.Build.class)
public interface TextFileResourceLoader {
    TextResource loadFile(String description, @Nullable File sourceFile);
}
