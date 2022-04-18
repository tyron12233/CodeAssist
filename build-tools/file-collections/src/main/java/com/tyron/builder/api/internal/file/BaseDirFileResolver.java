package com.tyron.builder.api.internal.file;


import java.io.File;

public class BaseDirFileResolver extends AbstractBaseDirFileResolver {
    private final File baseDir;

    /**
     * Do not create instances of this type. Use {@link FileLookup} instead.
     */
    public BaseDirFileResolver(File baseDir) {
        assert baseDir.isAbsolute() : String.format("base dir '%s' is not an absolute file.", baseDir);
        this.baseDir = baseDir;
    }

    @Override
    protected File getBaseDir() {
        return baseDir;
    }
}