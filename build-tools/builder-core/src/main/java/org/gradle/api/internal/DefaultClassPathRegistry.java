package org.gradle.api.internal;

import org.gradle.internal.classpath.ClassPath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DefaultClassPathRegistry implements ClassPathRegistry {
    private final List<ClassPathProvider> providers = new ArrayList<ClassPathProvider>();

    public DefaultClassPathRegistry(ClassPathProvider... providers) {
        this.providers.addAll(Arrays.asList(providers));
    }

    @Override
    public ClassPath getClassPath(String name) {
        for (ClassPathProvider provider : providers) {
            ClassPath classpath = provider.findClassPath(name);
            if (classpath != null) {
                return classpath;
            }
        }
        throw new IllegalArgumentException(String.format("unknown classpath '%s' requested.", name));
    }
}
