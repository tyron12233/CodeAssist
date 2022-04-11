package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.file.FileVisitDetails;
import com.tyron.builder.api.file.FileVisitor;
import com.tyron.builder.api.internal.Cast;
import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.tasks.util.PatternFilterable;
import com.tyron.builder.api.tasks.util.PatternSet;

import java.util.List;
import java.util.function.Consumer;

/**
 * A {@link FileTree} that contains the union of zero or more file trees.
 */
public abstract class CompositeFileTree extends CompositeFileCollection implements FileTreeInternal {
    public CompositeFileTree(Factory<PatternSet> patternSetFactory) {
        super(patternSetFactory);
    }

    public CompositeFileTree() {
        super();
    }

    @Override
    protected List<? extends FileTreeInternal> getSourceCollections() {
        return Cast.uncheckedNonnullCast(super.getSourceCollections());
    }

    @Override
    public FileTree plus(FileTree fileTree) {
        return new UnionFileTree(this, Cast.cast(FileTreeInternal.class, fileTree));
    }
//
//    @Override
//    public FileTree matching(final Closure filterConfigClosure) {
//        return new FilteredFileTree(this, patternSetFactory, () -> {
//            // For backwards compatibility, run the closure each time the file tree contents are queried
//            return configure(filterConfigClosure, patternSetFactory.create());
//        });
//    }

    @Override
    public FileTree matching(final Action<? super PatternFilterable> filterConfigAction) {
        return new FilteredFileTree(this, patternSetFactory, () -> {
            // For backwards compatibility, run the action each time the file tree contents are queried
            PatternSet patternSet = patternSetFactory.create();
            filterConfigAction.execute(patternSet);
            return patternSet;
        });
    }

    @Override
    public FileTreeInternal matching(final PatternFilterable patterns) {
        return new FilteredFileTree(this, patternSetFactory, () -> {
            if (patterns instanceof PatternSet) {
                return (PatternSet) patterns;
            }
            PatternSet patternSet = patternSetFactory.create();
            patternSet.copyFrom(patterns);
            return patternSet;
        });
    }

//    @Override
//    public FileTree visit(Closure visitor) {
//        return visit(fileVisitorFrom(visitor));
//    }

    @Override
    public FileTree visit(Action<? super FileVisitDetails> visitor) {
        for (FileTree tree : getSourceCollections()) {
            tree.visit(visitor);
        }
        return this;
    }

    @Override
    public FileTree visit(FileVisitor visitor) {
        for (FileTree tree : getSourceCollections()) {
            tree.visit(visitor);
        }
        return this;
    }

    @Override
    public FileTreeInternal getAsFileTree() {
        return this;
    }

    @Override
    public void visitContentsAsFileTrees(Consumer<FileTreeInternal> visitor) {
        visitChildren(child -> visitor.accept((FileTreeInternal) child));
    }
}