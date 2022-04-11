package com.tyron.builder.api.internal.file.collections;

import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.file.FileVisitor;
import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.internal.file.AbstractFileTree;
import com.tyron.builder.api.internal.file.FileCollectionStructureVisitor;
import com.tyron.builder.api.internal.file.FileTreeInternal;
import com.tyron.builder.api.internal.logging.TreeFormatter;
import com.tyron.builder.api.internal.tasks.TaskDependencyContainer;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.api.tasks.Buildable;
import com.tyron.builder.api.tasks.util.PatternFilterable;
import com.tyron.builder.api.tasks.util.PatternSet;

import java.io.File;
import java.util.function.Consumer;

/**
 * Adapts a {@link MinimalFileTree} into a full {@link FileTree} implementation.
 */
public final class FileTreeAdapter extends AbstractFileTree {
    private final MinimalFileTree tree;

    public FileTreeAdapter(MinimalFileTree tree, Factory<PatternSet> patternSetFactory) {
        super(patternSetFactory);
        this.tree = tree;
    }

    public MinimalFileTree getTree() {
        return tree;
    }

    @Override
    public String getDisplayName() {
        return tree.getDisplayName();
    }

    @Override
    protected void appendContents(TreeFormatter formatter) {
        formatter.node("tree: " + tree);
    }

    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
        if (tree instanceof Buildable) {
            context.add(tree);
        } else if (tree instanceof TaskDependencyContainer) {
            ((TaskDependencyContainer) tree).visitDependencies(context);
        }
    }

    @Override
    public boolean contains(File file) {
        if (tree instanceof RandomAccessFileCollection) {
            RandomAccessFileCollection randomAccess = (RandomAccessFileCollection) tree;
            return randomAccess.contains(file);
        }
        if (tree instanceof GeneratedSingletonFileTree) {
            return ((GeneratedSingletonFileTree) tree).getFileWithoutCreating().equals(file);
        }
        if (tree instanceof FileSystemMirroringFileTree) {
            return ((FileSystemMirroringFileTree) tree).getMirror().contains(file);
        }
        return super.contains(file);
    }

    @Override
    public FileTreeInternal matching(PatternFilterable patterns) {
        if (tree instanceof PatternFilterableFileTree) {
            PatternFilterableFileTree filterableTree = (PatternFilterableFileTree) tree;
            return new FileTreeAdapter(filterableTree.filter(patterns), patternSetFactory);
        } else if (tree instanceof FileSystemMirroringFileTree) {
            return new FileTreeAdapter(new FilteredMinimalFileTree((PatternSet) patterns, (FileSystemMirroringFileTree) tree), patternSetFactory);
        }
        throw new UnsupportedOperationException(String.format("Do not know how to filter %s.", tree));
    }

    @Override
    public FileTree visit(FileVisitor visitor) {
        tree.visit(visitor);
        return this;
    }

    @Override
    public void visitContentsAsFileTrees(Consumer<FileTreeInternal> visitor) {
        visitor.accept(this);
    }

    @Override
    protected void visitContents(FileCollectionStructureVisitor visitor) {
        tree.visitStructure(new MinimalFileTree.MinimalFileTreeStructureVisitor() {
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
        }, this);
    }
}