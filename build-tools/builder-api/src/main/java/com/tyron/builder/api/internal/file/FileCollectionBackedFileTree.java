package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.file.FileVisitor;
import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTree;
import com.tyron.builder.api.internal.file.collections.FileSystemMirroringFileTree;
import com.tyron.builder.api.internal.file.collections.FileTreeAdapter;
import com.tyron.builder.api.internal.logging.TreeFormatter;
import com.tyron.builder.api.internal.nativeintegration.services.FileSystems;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.api.tasks.util.PatternFilterable;
import com.tyron.builder.api.tasks.util.PatternSet;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public class FileCollectionBackedFileTree extends AbstractFileTree {
    private final AbstractFileCollection collection;

    public FileCollectionBackedFileTree(Factory<PatternSet> patternSetFactory, AbstractFileCollection collection) {
        super(patternSetFactory);
        this.collection = collection;
    }

    @Override
    protected void appendContents(TreeFormatter formatter) {
        formatter.node("backing collection");
        formatter.startChildren();
        collection.describeContents(formatter);
        formatter.endChildren();
    }

    public AbstractFileCollection getCollection() {
        return collection;
    }

    @Override
    public FileTreeInternal matching(PatternFilterable patterns) {
        return new FilteredFileTree(this, patternSetFactory, () -> {
            PatternSet patternSet = patternSetFactory.create();
            patternSet.copyFrom(patterns);
            return patternSet;
        });
    }

    @Override
    public FileTree visit(FileVisitor visitor) {
        visitContentsAsFileTrees(child -> child.visit(visitor));
        return this;
    }

    @Override
    public void visitContentsAsFileTrees(Consumer<FileTreeInternal> visitor) {
        visitContents(new FileCollectionStructureVisitor() {
            @Override
            public void visitCollection(Source source, Iterable<File> contents) {
                throw new UnsupportedOperationException("Should not be called");
            }

            @Override
            public void visitGenericFileTree(FileTreeInternal fileTree, FileSystemMirroringFileTree sourceTree) {
                visitor.accept(fileTree);
            }

            @Override
            public void visitFileTree(File root, PatternSet patterns, FileTreeInternal fileTree) {
                visitor.accept(fileTree);
            }

            @Override
            public void visitFileTreeBackedByFile(File file, FileTreeInternal fileTree, FileSystemMirroringFileTree sourceTree) {
                visitor.accept(fileTree);
            }
        });
    }

    @Override
    protected void visitContents(FileCollectionStructureVisitor visitor) {
        collection.visitStructure(new FileCollectionStructureVisitor() {
            final Set<File> seen = new HashSet<>();

            @Override
            public void visitCollection(Source source, Iterable<File> contents) {
                PatternSet patterns = patternSetFactory.create();
                for (File file : contents) {
                    if (seen.add(file)) {
                        new FileTreeAdapter(new DirectoryFileTree(file, patterns, FileSystems.getDefault()), patternSetFactory).visitStructure(visitor);
                    }
                }
            }

            @Override
            public void visitGenericFileTree(FileTreeInternal fileTree, FileSystemMirroringFileTree sourceTree) {
                visitor.visitGenericFileTree(fileTree, sourceTree);
            }

            @Override
            public void visitFileTree(File root, PatternSet patterns, FileTreeInternal fileTree) {
                visitor.visitFileTree(root, patterns, fileTree);
            }

            @Override
            public void visitFileTreeBackedByFile(File file, FileTreeInternal fileTree, FileSystemMirroringFileTree sourceTree) {
                visitor.visitFileTreeBackedByFile(file, fileTree, sourceTree);
            }
        });
    }

    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
        context.add(collection);
    }

    @Override
    public String getDisplayName() {
        return collection.getDisplayName();
    }
}
