package com.tyron.builder.api.internal.provider.sources;

import java.io.File;

import static com.tyron.builder.util.internal.GFileUtils.readFile;

public abstract class FileTextValueSource extends FileContentValueSource<String> {

    @Override
    protected String obtainFrom(File file) {
        return readFile(file);
    }
}
