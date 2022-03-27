package com.tyron.builder.api.internal.file;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.internal.tasks.TaskDependencyContainer;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.api.tasks.util.PatternSet;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A {@link org.gradle.api.file.FileCollection} that contains the union of zero or more file collections. Maintains file ordering.
 *
 * <p>The source file collections are calculated from the result of calling {@link #visitChildren(Consumer)}, and may be lazily created.
 * </p>
 *
 * <p>The dependencies of this collection are calculated from the result of calling {@link #visitDependencies(TaskDependencyResolveContext)}.</p>
 */
public abstract class CompositeFileCollection extends AbstractFileCollection implements TaskDependencyContainer {
    public CompositeFileCollection(Factory<PatternSet> patternSetFactory) {
        super(patternSetFactory);
    }

    public CompositeFileCollection() {
    }

    @Override
    public boolean contains(File file) {
        for (FileCollection collection : getSourceCollections()) {
            if (collection.contains(file)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isEmpty() {
        for (FileCollection collection : getSourceCollections()) {
            if (!collection.isEmpty()) {
                return false;
            }
        }
        return true;
    }

//    @Override
//    protected void addAsResourceCollection(Object builder, String nodeName) {
//        for (FileCollection fileCollection : getSourceCollections()) {
//            fileCollection.addToAntBuilder(builder, nodeName, AntType.ResourceCollection);
//        }
//    }

    @Override
    public FileCollectionInternal filter(final Predicate<? super File> filterSpec) {
        return new CompositeFileCollection(patternSetFactory) {
            @Override
            public FileCollectionInternal replace(FileCollectionInternal original, Supplier<FileCollectionInternal> supplier) {
                FileCollectionInternal newCollection = CompositeFileCollection.this.replace(original, supplier);
                if (newCollection == CompositeFileCollection.this) {
                    return this;
                }
                return newCollection.filter(filterSpec);
            }

            @Override
            protected void visitChildren(Consumer<FileCollectionInternal> visitor) {
                CompositeFileCollection.this.visitChildren(child -> visitor.accept(child.filter(filterSpec)));
            }

            @Override
            public void visitDependencies(TaskDependencyResolveContext context) {
                CompositeFileCollection.this.visitDependencies(context);
            }

            @Override
            public String getDisplayName() {
                return CompositeFileCollection.this.getDisplayName();
            }
        };
    }

    abstract protected void visitChildren(Consumer<FileCollectionInternal> visitor);

    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
        visitChildren(context::add);
    }

    protected List<? extends FileCollectionInternal> getSourceCollections() {
        ImmutableList.Builder<FileCollectionInternal> builder = ImmutableList.builder();
        visitChildren(builder::add);
        return builder.build();
    }

    @Override
    protected void visitContents(FileCollectionStructureVisitor visitor) {
        visitChildren(child -> child.visitStructure(visitor));
    }
}