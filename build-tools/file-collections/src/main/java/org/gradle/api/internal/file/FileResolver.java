package org.gradle.api.internal.file;

import org.gradle.api.PathValidation;
import org.gradle.internal.file.RelativeFilePathResolver;
import org.gradle.internal.typeconversion.NotationParser;
import org.gradle.internal.file.PathToFileResolver;

import java.io.File;
import java.net.URI;

public interface FileResolver extends RelativeFilePathResolver, PathToFileResolver {
    File resolve(Object path, PathValidation validation);

    URI resolveUri(Object path);

    NotationParser<Object, File> asNotationParser();

    @Override
    FileResolver newResolver(File baseDir);
}