package com.tyron.builder.api.internal.file.copy;

import com.tyron.builder.api.file.FileTreeElement;
import com.tyron.builder.api.file.FileVisitDetails;
import com.tyron.builder.api.file.FileVisitor;
import com.tyron.builder.api.file.RelativePath;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTreeFactory;
import com.tyron.builder.api.internal.file.collections.MinimalFileTree;
import com.tyron.builder.api.specs.Spec;
import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.api.tasks.WorkResults;
import com.tyron.builder.api.tasks.util.PatternFilterable;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.internal.file.Deleter;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

public class SyncCopyActionDecorator implements CopyAction {
    private final File baseDestDir;
    private final CopyAction delegate;
    private final PatternFilterable preserveSpec;
    private final Deleter deleter;
    private final DirectoryFileTreeFactory directoryFileTreeFactory;

    public SyncCopyActionDecorator(
        File baseDestDir,
        CopyAction delegate,
        Deleter deleter,
        DirectoryFileTreeFactory directoryFileTreeFactory
    ) {
        this(baseDestDir, delegate, null, deleter, directoryFileTreeFactory);
    }

    public SyncCopyActionDecorator(
        File baseDestDir,
        CopyAction delegate,
        @Nullable PatternFilterable preserveSpec,
        Deleter deleter,
        DirectoryFileTreeFactory directoryFileTreeFactory
    ) {
        this.baseDestDir = baseDestDir;
        this.delegate = delegate;
        this.preserveSpec = preserveSpec;
        this.deleter = deleter;
        this.directoryFileTreeFactory = directoryFileTreeFactory;
    }

    @Override
    public WorkResult execute(final CopyActionProcessingStream stream) {
        final Set<RelativePath> visited = new HashSet<>();

        WorkResult didWork = delegate.execute(action -> stream.process(details -> {
            visited.add(details.getRelativePath());
            action.processFile(details);
        }));

        SyncCopyActionDecoratorFileVisitor fileVisitor = new SyncCopyActionDecoratorFileVisitor(visited, preserveSpec, deleter);

        MinimalFileTree walker = directoryFileTreeFactory.create(baseDestDir).postfix();
        walker.visit(fileVisitor);
        visited.clear();

        return WorkResults.didWork(didWork.getDidWork() || fileVisitor.didWork);
    }

    private static class SyncCopyActionDecoratorFileVisitor implements FileVisitor {
        private final Set<RelativePath> visited;
        private final Predicate<FileTreeElement> preserveSpec;
        private final PatternSet preserveSet;
        private final Deleter deleter;
        private boolean didWork;

        private SyncCopyActionDecoratorFileVisitor(Set<RelativePath> visited, @Nullable PatternFilterable preserveSpec, Deleter deleter) {
            this.visited = visited;
            this.deleter = deleter;
            PatternSet preserveSet = new PatternSet();
            if (preserveSpec != null) {
                preserveSet.include(preserveSpec.getIncludes());
                preserveSet.exclude(preserveSpec.getExcludes());
            }
            this.preserveSet = preserveSet;
            this.preserveSpec = preserveSet.getAsSpec();
        }

        @Override
        public void visitDir(FileVisitDetails dirDetails) {
            maybeDelete(dirDetails);
        }

        @Override
        public void visitFile(FileVisitDetails fileDetails) {
            maybeDelete(fileDetails);
        }

        private void maybeDelete(FileVisitDetails fileDetails) {
            RelativePath path = fileDetails.getRelativePath();
            if (!visited.contains(path)) {
                if (preserveSet.isEmpty() || !preserveSpec.test(fileDetails)) {
                    try {
                        didWork = deleter.deleteRecursively(fileDetails.getFile());
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }
            }
        }
    }
}
