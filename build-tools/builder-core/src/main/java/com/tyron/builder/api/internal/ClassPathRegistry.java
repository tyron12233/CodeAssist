package com.tyron.builder.api.internal;

import com.tyron.builder.internal.classpath.ClassPath;

public interface ClassPathRegistry {
    ClassPath getClassPath(String name);
}
