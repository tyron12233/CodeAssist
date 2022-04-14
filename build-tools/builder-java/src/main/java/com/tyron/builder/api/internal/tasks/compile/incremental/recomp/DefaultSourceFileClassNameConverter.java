package com.tyron.builder.api.internal.tasks.compile.incremental.recomp;

import java.util.Map;
import java.util.Set;


import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class DefaultSourceFileClassNameConverter implements SourceFileClassNameConverter {
    private final Map<String, Set<String>> sourceClassesMapping;
    private final Map<String, Set<String>> classSourceMapping;

    public DefaultSourceFileClassNameConverter(Map<String, Set<String>> sourceClassesMapping) {
        this.sourceClassesMapping = sourceClassesMapping;
        this.classSourceMapping = constructReverseMapping(sourceClassesMapping);
    }

    private Map<String, Set<String>> constructReverseMapping(Map<String, Set<String>> sourceClassesMapping) {
        Map<String, Set<String>> reverse = new HashMap<>();
        for (Map.Entry<String, ? extends Collection<String>> entry : sourceClassesMapping.entrySet()) {
            for (String cls : entry.getValue()) {
                reverse.computeIfAbsent(cls, key -> new HashSet<>()).add(entry.getKey());
            }
        }
        return reverse;
    }

    @Override
    public Set<String> getClassNames(String sourceFileRelativePath) {
        return sourceClassesMapping.getOrDefault(sourceFileRelativePath, Collections.emptySet());
    }

    public Set<String> getRelativeSourcePaths(String fqcn) {
        return classSourceMapping.getOrDefault(fqcn, Collections.emptySet());
    }

}
