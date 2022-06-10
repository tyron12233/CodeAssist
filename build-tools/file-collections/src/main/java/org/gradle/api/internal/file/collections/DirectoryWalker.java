package org.gradle.api.internal.file.collections;


import org.gradle.api.file.FileTreeElement;
import org.gradle.api.file.FileVisitor;
import org.gradle.api.file.RelativePath;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

public interface DirectoryWalker {
    void walkDir(File file, RelativePath path, FileVisitor visitor, Predicate<? super FileTreeElement> spec, AtomicBoolean stopFlag, boolean postfix);
}