package com.tyron.builder.api.internal.tasks.compile.incremental.recomp;

import com.tyron.builder.util.GStringUtils;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A converter which infers the class names from the file name.
 */
public class FileNameDerivingClassNameConverter implements SourceFileClassNameConverter {
    private final SourceFileClassNameConverter delegate;
    private final Set<String> fileExtensions;

    public FileNameDerivingClassNameConverter(SourceFileClassNameConverter delegate, Set<String> fileExtensions) {
        this.delegate = delegate;
        this.fileExtensions = fileExtensions;
    }

    @Override
    public Set<String> getClassNames(String sourceFileRelativePath) {
        Set<String> classNames = delegate.getClassNames(sourceFileRelativePath);
        if (!classNames.isEmpty()) {
            return classNames;
        }

        for (String fileExtension : fileExtensions) {
            if (sourceFileRelativePath.endsWith(fileExtension)) {
                return Collections.singleton(GStringUtils.removeEnd(sourceFileRelativePath.replace('/', '.'), fileExtension));
            }
        }

        return Collections.emptySet();
    }

    @Override
    public Set<String> getRelativeSourcePaths(String className) {
        Set<String> sourcePaths = delegate.getRelativeSourcePaths(className);
        if (!sourcePaths.isEmpty()) {
            return sourcePaths;
        }

        Set<String> paths = fileExtensions.stream()
                .map(fileExtension -> classNameToRelativePath(className, fileExtension))
                .collect(Collectors.toSet());

        // Classes with $ may be inner classes
        int innerClassIdx = className.indexOf("$");
        if (innerClassIdx > 0) {
            String baseName = className.substring(0, innerClassIdx);
            fileExtensions.stream()
                    .map(fileExtension -> classNameToRelativePath(baseName, fileExtension))
                    .forEach(paths::add);
        }

        return paths;
    }

    private String classNameToRelativePath(String className, String fileExtension) {
        return className.replace('.', '/') + fileExtension;
    }
}
