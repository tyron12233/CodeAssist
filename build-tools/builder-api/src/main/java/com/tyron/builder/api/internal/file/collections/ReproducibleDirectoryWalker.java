package com.tyron.builder.api.internal.file.collections;

import com.tyron.builder.api.file.FileTreeElement;
import com.tyron.builder.api.file.FileVisitDetails;
import com.tyron.builder.api.file.FileVisitor;
import com.tyron.builder.api.file.RelativePath;
import com.tyron.builder.api.internal.file.DefaultFileVisitDetails;
import com.tyron.builder.api.internal.nativeintegration.FileSystem;

import org.apache.commons.io.comparator.PathFileComparator;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

public class ReproducibleDirectoryWalker implements DirectoryWalker {
    private final FileSystem fileSystem;

    public ReproducibleDirectoryWalker(FileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }

    protected File[] getChildren(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            Arrays.sort(children, PathFileComparator.PATH_COMPARATOR);
        }
        return children;
    }

    @Override
    public void walkDir(File file, RelativePath path, FileVisitor visitor, Predicate<? super FileTreeElement> spec, AtomicBoolean stopFlag, boolean postfix) {
        File[] children = getChildren(file);
        if (children == null) {
            if (file.isDirectory() && !file.canRead()) {
                throw new RuntimeException(String.format("Could not list contents of directory '%s' as it is not readable.", file));
            }
            // else, might be a link which points to nothing, or has been removed while we're visiting, or ...
            throw new RuntimeException(String.format("Could not list contents of '%s'.", file));
        }
        List<FileVisitDetails> dirs = new ArrayList<FileVisitDetails>();
        for (int i = 0; !stopFlag.get() && i < children.length; i++) {
            File child = children[i];
            boolean isFile = child.isFile();
            RelativePath childPath = path.append(isFile, child.getName());
            FileVisitDetails details = new DefaultFileVisitDetails(child, childPath, stopFlag, fileSystem, fileSystem);
            if (DirectoryFileTree.isAllowed(details, spec)) {
                if (isFile) {
                    visitor.visitFile(details);
                } else {
                    dirs.add(details);
                }
            }
        }

        // now handle dirs
        for (int i = 0; !stopFlag.get() && i < dirs.size(); i++) {
            FileVisitDetails dir = dirs.get(i);
            if (postfix) {
                walkDir(dir.getFile(), dir.getRelativePath(), visitor, spec, stopFlag, postfix);
                visitor.visitDir(dir);
            } else {
                visitor.visitDir(dir);
                walkDir(dir.getFile(), dir.getRelativePath(), visitor, spec, stopFlag, postfix);
            }
        }
    }
}