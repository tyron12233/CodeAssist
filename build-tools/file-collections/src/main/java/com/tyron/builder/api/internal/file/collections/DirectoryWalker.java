package com.tyron.builder.api.internal.file.collections;


import com.tyron.builder.api.file.FileTreeElement;
import com.tyron.builder.api.file.FileVisitor;
import com.tyron.builder.api.file.RelativePath;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

public interface DirectoryWalker {
    void walkDir(File file, RelativePath path, FileVisitor visitor, Predicate<? super FileTreeElement> spec, AtomicBoolean stopFlag, boolean postfix);
}