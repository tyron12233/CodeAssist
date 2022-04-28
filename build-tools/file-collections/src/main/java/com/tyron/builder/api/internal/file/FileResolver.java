package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.PathValidation;
import com.tyron.builder.internal.file.RelativeFilePathResolver;
import com.tyron.builder.internal.typeconversion.NotationParser;
import com.tyron.builder.internal.file.PathToFileResolver;

import java.io.File;
import java.net.URI;

public interface FileResolver extends RelativeFilePathResolver, PathToFileResolver {
    File resolve(Object path, PathValidation validation);

    URI resolveUri(Object path);

    NotationParser<Object, File> asNotationParser();

    @Override
    FileResolver newResolver(File baseDir);
}