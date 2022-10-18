package org.gradle.api.internal.provider.sources;

import java.io.File;

import static org.gradle.util.internal.GFileUtils.readFile;

public abstract class FileTextValueSource extends FileContentValueSource<String> {

    @Override
    protected String obtainFrom(File file) {
        return readFile(file);
    }
}
