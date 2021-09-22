package com.tyron.kotlin_completion.classpath;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class DefaultClassPathResolver implements ClassPathResolver {

    private final Collection<File> mLibraries;

    public DefaultClassPathResolver(Collection<File> libraries) {
        mLibraries = libraries;
    }

    @Override
    public String getResolveType() {
        return "Default";
    }

    @Override
    public Set<ClassPathEntry> getClassPath() {
        return mLibraries.stream().map(file -> new ClassPathEntry(file.toPath(), null)).collect(Collectors.toSet());
    }

    @Override
    public Set<ClassPathEntry> getClassPathWithSources() {
        return mLibraries.stream().map(file -> {
            File source = new File(file.getParentFile(), file.getName().substring(0, file.getName().lastIndexOf(".")) + "-sources.jar");
            return new ClassPathEntry(file.toPath(), (source.exists() ? source.toPath() : null));
        }).collect(Collectors.toSet());
    }
}
