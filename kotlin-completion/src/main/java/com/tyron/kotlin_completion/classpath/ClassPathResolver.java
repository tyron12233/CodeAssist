package com.tyron.kotlin_completion.classpath;

import java.util.Collections;
import java.util.Set;

public interface ClassPathResolver {

    ClassPathResolver EMPTY = new ClassPathResolver() {
        @Override
        public String getResolveType() {
            return "[]";
        }

        @Override
        public Set<ClassPathEntry> getClassPath() {
            return Collections.emptySet();
        }
    };

    String getResolveType();

    Set<ClassPathEntry> getClassPath();

    default Set<ClassPathEntry> getClassPathOrEmpty() {
        try {
            return getClassPath();
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    default Set<ClassPathEntry> getClassPathWithSources() {
        return getClassPath();
    }


}
