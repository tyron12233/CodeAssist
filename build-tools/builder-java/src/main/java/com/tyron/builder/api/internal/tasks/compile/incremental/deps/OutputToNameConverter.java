package com.tyron.builder.api.internal.tasks.compile.incremental.deps;


import static com.tyron.builder.util.RelativePathUtil.relativePath;

import java.io.File;

class OutputToNameConverter {

    private final File compiledClassesDir;

    public OutputToNameConverter(File compiledClassesDir) {
        this.compiledClassesDir = compiledClassesDir;
    }

    public String getClassName(File classFile) {
        String path = relativePath(compiledClassesDir, classFile);
        if (path.startsWith("/") || path.startsWith(".")) {
            throw new IllegalArgumentException("Given input class file: '" + classFile + "' is not located inside of '" + compiledClassesDir + "'.");
        }
        return path.replaceAll("/", ".").replaceAll("\\.class", "");
    }
}

