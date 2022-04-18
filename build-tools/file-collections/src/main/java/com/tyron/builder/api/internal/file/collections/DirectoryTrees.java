package com.tyron.builder.api.internal.file.collections;

import com.tyron.builder.api.file.DirectoryTree;
import com.tyron.builder.api.file.RelativePath;
import com.tyron.builder.api.internal.file.DefaultFileTreeElement;
import com.tyron.builder.api.internal.nativeintegration.FileSystem;

import java.io.File;

public abstract class DirectoryTrees {
    private DirectoryTrees() {
    }

    public static boolean contains(FileSystem fileSystem, DirectoryTree tree, File file) {
        String prefix = tree.getDir().getAbsolutePath() + File.separator;
        if (!file.getAbsolutePath().startsWith(prefix)) {
            return false;
        }

        RelativePath path = RelativePath.parse(true, file.getAbsolutePath().substring(prefix.length()));
        return tree.getPatterns().getAsSpec().test(new DefaultFileTreeElement(file, path, fileSystem, fileSystem));
    }

}