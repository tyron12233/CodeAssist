package com.tyron.builder.api.internal.file.collections;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.file.DirectoryTree;
import com.tyron.builder.util.internal.DeferredUtil;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.api.internal.file.AbstractOpaqueFileCollection;
import com.tyron.builder.api.internal.file.CompositeFileCollection;
import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.internal.file.PathToFileResolver;
import com.tyron.builder.api.internal.provider.ProviderInternal;
import com.tyron.builder.api.internal.tasks.TaskDependencyContainer;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.api.provider.ProviderResolutionStrategy;
import com.tyron.builder.api.Buildable;
import com.tyron.builder.api.tasks.TaskOutputs;
import com.tyron.builder.api.tasks.util.PatternSet;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;

public class UnpackingVisitor {
    private final Consumer<FileCollectionInternal> visitor;
    private final PathToFileResolver resolver;
    private final Factory<PatternSet> patternSetFactory;
    private final boolean includeBuildable;
    private final ProviderResolutionStrategy providerResolutionStrategy;

    public UnpackingVisitor(Consumer<FileCollectionInternal> visitor, PathToFileResolver resolver, Factory<PatternSet> patternSetFactory) {
        this(visitor, resolver, patternSetFactory, ProviderResolutionStrategy.REQUIRE_PRESENT, true);
    }

    public UnpackingVisitor(Consumer<FileCollectionInternal> visitor, PathToFileResolver resolver, Factory<PatternSet> patternSetFactory, ProviderResolutionStrategy providerResolutionStrategy, boolean includeBuildable) {
        this.visitor = visitor;
        this.resolver = resolver;
        this.patternSetFactory = patternSetFactory;
        this.providerResolutionStrategy = providerResolutionStrategy;
        this.includeBuildable = includeBuildable;
    }

    public void add(@Nullable Object element) {
        if (element instanceof FileCollectionInternal) {
            // FileCollection is-a Iterable, Buildable and TaskDependencyContainer, so check before checking for these things
            visitor.accept((FileCollectionInternal) element);
            return;
        }
        if (element instanceof DirectoryTree) {
            visitor.accept(new FileTreeAdapter((MinimalFileTree) element, patternSetFactory));
            return;
        }
        if (element instanceof ProviderInternal) {
            // ProviderInternal is-a TaskDependencyContainer, so check first
            ProviderInternal<?> provider = (ProviderInternal<?>) element;
            visitor.accept(new ProviderBackedFileCollection(provider, resolver, patternSetFactory, providerResolutionStrategy));
            return;
        }
        if (includeBuildable && (element instanceof Buildable || element instanceof TaskDependencyContainer)) {
            visitor.accept(new BuildableElementFileCollection(element, resolver, patternSetFactory));
            return;
        }

        if (element instanceof Task) {
            visitor.accept((FileCollectionInternal) ((Task) element).getOutputs().getFiles());
        } else if (element instanceof TaskOutputs) {
            visitor.accept((FileCollectionInternal) ((TaskOutputs) element).getFiles());
        } else if (DeferredUtil.isNestableDeferred(element)) {
            Object deferredResult = DeferredUtil.unpackNestableDeferred(element);
            if (deferredResult != null) {
                add(deferredResult);
            }
        } else if (element instanceof Path) {
            // Path is-a Iterable, so check before checking for Iterable
            visitSingleFile(element);
        } else if (element instanceof Iterable) {
            Iterable<?> iterable = (Iterable) element;
            for (Object item : iterable) {
                add(item);
            }
        } else if (element instanceof Object[]) {
            Object[] array = (Object[]) element;
            for (Object value : array) {
                add(value);
            }
        } else if (element != null) {
            // Treat everything else as a single file
            visitSingleFile(element);
        }
    }

    private void visitSingleFile(Object element) {
        visitor.accept(new SingleFileResolvingFileCollection(element, resolver, patternSetFactory));
    }

    private static class SingleFileResolvingFileCollection extends AbstractOpaqueFileCollection {
        private Object element;
        private final PathToFileResolver resolver;
        private File resolved;

        public SingleFileResolvingFileCollection(Object element, PathToFileResolver resolver, Factory<PatternSet> patternSetFactory) {
            super(patternSetFactory);
            this.element = element;
            this.resolver = resolver;
        }

        @Override
        public String getDisplayName() {
            return "file collection";
        }

        @Override
        protected Set<File> getIntrinsicFiles() {
            if (resolved == null) {
                resolved = resolver.resolve(element);
                element = null;
            }
            return ImmutableSet.of(resolved);
        }
    }

    private static class BuildableElementFileCollection extends CompositeFileCollection {
        private final Object element;
        private final PathToFileResolver resolver;
        private final Factory<PatternSet> patternSetFactory;

        public BuildableElementFileCollection(Object element, PathToFileResolver resolver, Factory<PatternSet> patternSetFactory) {
            this.element = element;
            this.resolver = resolver;
            this.patternSetFactory = patternSetFactory;
        }

        @Override
        public void visitDependencies(TaskDependencyResolveContext context) {
            context.add(element);
        }

        @Override
        public String getDisplayName() {
            return "file collection";
        }

        @Override
        protected void visitChildren(Consumer<FileCollectionInternal> visitor) {
            new UnpackingVisitor(visitor, resolver, patternSetFactory, ProviderResolutionStrategy.REQUIRE_PRESENT, false).add(element);
        }
    }
}