package com.tyron.builder.api.internal.file;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;

public abstract class AbstractBaseDirFileResolver extends AbstractFileResolver {
    protected abstract File getBaseDir();

    @Override
    public String resolveAsRelativePath(Object path) {
        Path baseDir = getBaseDir().toPath();
        Path file = resolve(path).toPath();
        if (file.equals(baseDir)) {
            return ".";
        } else {
            return baseDir.relativize(file).toString();
        }
    }

    @Override
    public String resolveForDisplay(Object path) {
        Path file = resolve(path).toPath();
        Path baseDir = getBaseDir().toPath();
        if (file.equals(baseDir)) {
            return ".";
        }
        Path parent = baseDir.getParent();
        if (parent == null) {
            parent = baseDir;
        }
        if (file.startsWith(parent)) {
            return baseDir.relativize(file).toString();
        } else {
            return file.toString();
        }
    }

    private boolean isTrue(Object object) {
        if (object == null) {
            return false;
        }

        if (object instanceof Collection) {
            return ((Collection<?>) object).size() > 0;
        } else if (object instanceof String) {
            return ((String) object).length() > 0;
        }
        return true;
    }

    @Override
    protected File doResolve(Object path) {
        if (!isTrue(path)) {
            throw new IllegalArgumentException(String.format(
                    "path may not be null or empty string. path='%s'", path));
        }

        File file = convertObjectToFile(path);

        if (file == null) {
            throw new IllegalArgumentException(String.format("Cannot convert path to File. path='%s'", path));
        }

        if (!file.isAbsolute()) {
            File baseDir = getBaseDir();
            if (!isTrue(baseDir)) {
                throw new IllegalArgumentException(String.format("baseDir may not be null or empty string. basedir='%s'", baseDir));
            }

            file = new File(baseDir, file.getPath());
        }

        return file;
    }

    @Override
    public boolean canResolveRelativePath() {
        return true;
    }
}