package com.tyron.builder.api.internal.file;

import java.io.File;

/**
 * FileResolver that uses the file provided to it or constructs one from the toString of the provided object. Used in cases where a FileResolver is needed by the infrastructure, but no base directory
 * can be known.
 *
 * NOTE: Do not create instances of this type. Instead, use the {@link FileLookup} service.
 */
public class IdentityFileResolver extends AbstractFileResolver {
    @Override
    protected File doResolve(Object path) {
        File file = convertObjectToFile(path);

        if (file == null) {
            throw new IllegalArgumentException(String.format(
                    "Cannot convert path to File. path='%s'", path));
        }

        if (!file.isAbsolute()) {
            throw new UnsupportedOperationException(String.format("Cannot convert relative path %s to an absolute file.", path));
        }
        return file;
    }

    @Override
    public String resolveAsRelativePath(Object path) {
        throw new UnsupportedOperationException(String.format("Cannot convert path %s to a relative path.", path));
    }

    @Override
    public boolean canResolveRelativePath() {
        return false;
    }
}