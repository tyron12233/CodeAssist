package com.tyron.builder.api.internal.file.collections;

import com.google.common.annotations.VisibleForTesting;
import com.tyron.builder.api.file.DirectoryTree;
import com.tyron.builder.api.file.FileTreeElement;
import com.tyron.builder.api.file.FileVisitDetails;
import com.tyron.builder.api.file.FileVisitor;
import com.tyron.builder.api.file.RelativePath;
import com.tyron.builder.api.file.ReproducibleFileVisitor;
import com.tyron.builder.api.internal.file.DefaultFileVisitDetails;
import com.tyron.builder.api.internal.file.FileTreeInternal;
import com.tyron.builder.api.internal.nativeintegration.FileSystem;
import com.tyron.builder.api.internal.nativeintegration.services.FileSystems;
import com.tyron.builder.api.tasks.util.PatternFilterable;
import com.tyron.builder.api.tasks.util.PatternSet;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Directory walker supporting {@link Spec}s for includes and excludes.
 * The file system is traversed in depth-first prefix order - all files in a directory will be
 * visited before any child directory is visited.
 *
 * A file or directory will only be visited if it matches all includes and no
 * excludes.
 */
public class DirectoryFileTree implements MinimalFileTree, PatternFilterableFileTree, RandomAccessFileCollection, LocalFileTree, DirectoryTree {
    private static final Logger LOGGER = Logger.getLogger("DirectoryFileTree");
    private static final DirectoryWalker DEFAULT_DIRECTORY_WALKER = new DefaultDirectoryWalker(
            FileSystems.getDefault());
    private static final DirectoryWalker REPRODUCIBLE_DIRECTORY_WALKER = new ReproducibleDirectoryWalker(FileSystems.getDefault());

    private final File dir;
    private final PatternSet patternSet;
    private final boolean postfix;
    private final FileSystem fileSystem;

    public DirectoryFileTree(File dir, PatternSet patternSet, FileSystem fileSystem) {
        this(dir, patternSet, fileSystem, false);
    }

    @VisibleForTesting
    public DirectoryFileTree(File dir, PatternSet patternSet, FileSystem fileSystem, boolean postfix) {
        this.patternSet = patternSet;
        this.dir = dir;
        this.fileSystem = fileSystem;
        this.postfix = postfix;
    }

    @Override
    public String getDisplayName() {
        String includes = patternSet.getIncludes().isEmpty() ? "" : String.format(" include %s", patternSet.getIncludes().toString());
        String excludes = patternSet.getExcludes().isEmpty() ? "" : String.format(" exclude %s", patternSet.getExcludes().toString());
        return String.format("directory '%s'%s%s", dir, includes, excludes);
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    @Override
    public PatternSet getPatterns() {
        return patternSet;
    }

    @Override
    public File getDir() {
        return dir;
    }

    @Override
    public DirectoryFileTree filter(PatternFilterable patterns) {
        PatternSet patternSet = this.patternSet.intersect();
        patternSet.copyFrom(patterns);
        return new DirectoryFileTree(dir, patternSet, fileSystem, postfix);
    }

    @Override
    public boolean contains(File file) {
        return DirectoryTrees.contains(fileSystem, this, file) && file.isFile();
    }

    @Override
    public void visitStructure(MinimalFileTreeStructureVisitor visitor, FileTreeInternal owner) {
        visitor.visitFileTree(dir, patternSet, owner);
    }

    @Override
    public void visit(FileVisitor visitor) {
        visitFrom(visitor, dir, RelativePath.EMPTY_ROOT);
    }

    /**
     * Process the specified file or directory.  If it is a directory, then its contents
     * (but not the directory itself) will be checked with {@link #isAllowed(FileTreeElement, Spec)} and notified to
     * the listener.  If it is a file, the file will be checked and notified.
     */
    public void visitFrom(FileVisitor visitor, File fileOrDirectory, RelativePath path) {
        AtomicBoolean stopFlag = new AtomicBoolean();
        Predicate<FileTreeElement> spec = patternSet.getAsSpec();
        if (fileOrDirectory.exists()) {
            if (fileOrDirectory.isFile()) {
                processSingleFile(fileOrDirectory, visitor, spec, stopFlag);
            } else {
                walkDir(fileOrDirectory, path, visitor, spec, stopFlag);
            }
        } else {
            LOGGER.info("file or directory '" + fileOrDirectory + "', not found");
        }
    }

    private void processSingleFile(File file, FileVisitor visitor, Predicate<FileTreeElement> spec, AtomicBoolean stopFlag) {
        RelativePath path = new RelativePath(true, file.getName());
        FileVisitDetails
                details = new DefaultFileVisitDetails(file, path, stopFlag, fileSystem, fileSystem);
        if (isAllowed(details, spec)) {
            visitor.visitFile(details);
        }
    }

    private void walkDir(File file, RelativePath path, FileVisitor visitor, Predicate<FileTreeElement> spec, AtomicBoolean stopFlag) {
        DirectoryWalker directoryWalker;
        if (visitor instanceof ReproducibleFileVisitor && ((ReproducibleFileVisitor) visitor).isReproducibleFileOrder()) {
            directoryWalker = REPRODUCIBLE_DIRECTORY_WALKER;
        } else {
            directoryWalker = DEFAULT_DIRECTORY_WALKER;
        }
        directoryWalker.walkDir(file, path, visitor, spec, stopFlag, postfix);
    }

    static boolean isAllowed(FileTreeElement element, Predicate<? super FileTreeElement> spec) {
        return spec.test(element);
    }

    /**
     * Returns a copy that traverses directories (but not files) in postfix rather than prefix order.
     *
     * @return {@code this}
     */
    public DirectoryFileTree postfix() {
        if (postfix) {
            return this;
        }
        return new DirectoryFileTree(dir, patternSet, fileSystem, true);
    }

    public PatternSet getPatternSet() {
        return patternSet;
    }

}