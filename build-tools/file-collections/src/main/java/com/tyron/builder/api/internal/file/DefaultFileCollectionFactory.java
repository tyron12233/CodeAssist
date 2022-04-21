package com.tyron.builder.api.internal.file;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.file.ConfigurableFileCollection;
import com.tyron.builder.api.file.ConfigurableFileTree;
import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.file.FileVisitor;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.api.internal.file.collections.DefaultConfigurableFileCollection;
import com.tyron.builder.api.internal.file.collections.DefaultConfigurableFileTree;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTreeFactory;
import com.tyron.builder.api.internal.file.collections.FileCollectionAdapter;
import com.tyron.builder.api.internal.file.collections.FileTreeAdapter;
import com.tyron.builder.api.internal.file.collections.GeneratedSingletonFileTree;
import com.tyron.builder.api.internal.file.collections.MinimalFileSet;
import com.tyron.builder.api.internal.file.collections.MinimalFileTree;
import com.tyron.builder.api.internal.file.collections.UnpackingVisitor;
import com.tyron.builder.internal.logging.text.TreeFormatter;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.api.internal.provider.PropertyHost;
import com.tyron.builder.api.internal.tasks.TaskDependencyFactory;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.api.provider.ProviderResolutionStrategy;
import com.tyron.builder.api.tasks.TaskDependency;
import com.tyron.builder.api.tasks.util.PatternFilterable;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.internal.file.PathToFileResolver;

import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class DefaultFileCollectionFactory implements FileCollectionFactory {
    public static final String DEFAULT_COLLECTION_DISPLAY_NAME = "file collection";
    public static final String DEFAULT_TREE_DISPLAY_NAME = "file tree";
    private static final EmptyFileCollection EMPTY_COLLECTION = new EmptyFileCollection(DEFAULT_COLLECTION_DISPLAY_NAME);
    private final PathToFileResolver fileResolver;
    private final TaskDependencyFactory taskDependencyFactory;
    private final DirectoryFileTreeFactory directoryFileTreeFactory;
    private final Factory<PatternSet> patternSetFactory;
    private final PropertyHost propertyHost;
    private final FileSystem fileSystem;

    public DefaultFileCollectionFactory(PathToFileResolver fileResolver, TaskDependencyFactory taskDependencyFactory, DirectoryFileTreeFactory directoryFileTreeFactory, Factory<PatternSet> patternSetFactory,
                                        PropertyHost propertyHost, FileSystem fileSystem) {
        this.fileResolver = fileResolver;
        this.taskDependencyFactory = taskDependencyFactory;
        this.directoryFileTreeFactory = directoryFileTreeFactory;
        this.patternSetFactory = patternSetFactory;
        this.propertyHost = propertyHost;
        this.fileSystem = fileSystem;
    }

    @Override
    public FileCollectionFactory withResolver(PathToFileResolver fileResolver) {
        if (fileResolver == this.fileResolver) {
            return this;
        }
        return new DefaultFileCollectionFactory(fileResolver, taskDependencyFactory, directoryFileTreeFactory, patternSetFactory, propertyHost, fileSystem);
    }

    @Override
    public ConfigurableFileCollection configurableFiles() {
        return new DefaultConfigurableFileCollection(null, fileResolver, taskDependencyFactory, patternSetFactory, propertyHost);
    }

    @Override
    public ConfigurableFileCollection configurableFiles(String displayName) {
        return new DefaultConfigurableFileCollection(displayName, fileResolver, taskDependencyFactory, patternSetFactory, propertyHost);
    }

    @Override
    public ConfigurableFileTree fileTree() {
        return new DefaultConfigurableFileTree(fileResolver, patternSetFactory, taskDependencyFactory, directoryFileTreeFactory);
    }

    @Override
    public FileTreeInternal treeOf(List<? extends FileTreeInternal> fileTrees) {
        if (fileTrees.isEmpty()) {
            return new EmptyFileTree();
        } else if (fileTrees.size() == 1) {
            return fileTrees.get(0);
        } else {
            return new DefaultCompositeFileTree(patternSetFactory, ImmutableList.copyOf(fileTrees));
        }
    }

    @Override
    public FileTreeInternal treeOf(MinimalFileTree tree) {
        return new FileTreeAdapter(tree, patternSetFactory);
    }

    @Override
    public FileCollectionInternal create(final TaskDependency builtBy, MinimalFileSet contents) {
        return new FileCollectionAdapter(contents, patternSetFactory) {
            @Override
            public void visitDependencies(TaskDependencyResolveContext context) {
                super.visitDependencies(context);
                context.add(builtBy);
            }
        };
    }

    @Override
    public FileCollectionInternal create(MinimalFileSet contents) {
        return new FileCollectionAdapter(contents, patternSetFactory);
    }

    @Override
    public FileCollectionInternal resolving(String displayName, Object sources) {
        return resolving(displayName, ProviderResolutionStrategy.REQUIRE_PRESENT, sources);
    }

    @Override
    public FileCollectionInternal resolvingLeniently(String displayName, Object sources) {
        return resolving(displayName, ProviderResolutionStrategy.ALLOW_ABSENT, sources);
    }

    private FileCollectionInternal resolving(String displayName, ProviderResolutionStrategy providerResolutionStrategy, Object sources) {
        if (isEmptyArray(sources)) {
            return empty(displayName);
        }
        return new ResolvingFileCollection(displayName, fileResolver, patternSetFactory, providerResolutionStrategy, sources);
    }

    @Override
    public FileCollectionInternal resolving(Object sources) {
        return resolving(ProviderResolutionStrategy.REQUIRE_PRESENT, sources);
    }

    @Override
    public FileCollectionInternal resolvingLeniently(Object sources) {
        return resolving(ProviderResolutionStrategy.ALLOW_ABSENT, sources);
    }

    private FileCollectionInternal resolving(ProviderResolutionStrategy providerResolutionStrategy, Object sources) {
        if (sources instanceof FileCollectionInternal) {
            return (FileCollectionInternal) sources;
        }
        if (isEmptyArray(sources)) {
            return empty();
        }
        return resolving(DEFAULT_COLLECTION_DISPLAY_NAME, providerResolutionStrategy, sources);
    }

    @Override
    public FileCollectionInternal empty(String displayName) {
        return new EmptyFileCollection(displayName);
    }

    @Override
    public FileCollectionInternal empty() {
        return EMPTY_COLLECTION;
    }

    @Override
    public FileCollectionInternal fixed(File... files) {
        if (files.length == 0) {
            return empty();
        }
        return fixed(DEFAULT_COLLECTION_DISPLAY_NAME, files);
    }

    @Override
    public FileCollectionInternal fixed(String displayName, File... files) {
        if (files.length == 0) {
            return new EmptyFileCollection(displayName);
        }
        return new FixedFileCollection(displayName, patternSetFactory, ImmutableSet.copyOf(files));
    }

    @Override
    public FileCollectionInternal fixed(Collection<File> files) {
        if (files.isEmpty()) {
            return empty();
        }
        return fixed(DEFAULT_COLLECTION_DISPLAY_NAME, files);
    }

    @Override
    public FileCollectionInternal fixed(final String displayName, Collection<File> files) {
        if (files.isEmpty()) {
            return new EmptyFileCollection(displayName);
        }
        return new FixedFileCollection(displayName, patternSetFactory, ImmutableSet.copyOf(files));
    }

    @Override
    public FileTreeInternal generated(Factory<File> tmpDir, String fileName, Action<File> fileGenerationListener, Action<OutputStream> contentWriter) {
        return new FileTreeAdapter(new GeneratedSingletonFileTree(tmpDir, fileName, fileGenerationListener, contentWriter, fileSystem), patternSetFactory);
    }

    private static final class EmptyFileCollection extends AbstractFileCollection {
        private final String displayName;

        public EmptyFileCollection(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }

        @Override
        public Set<File> getFiles() {
            return ImmutableSet.of();
        }

        @Override
        protected void visitContents(FileCollectionStructureVisitor visitor) {
        }

        @Override
        public FileTreeInternal getAsFileTree() {
            return new EmptyFileTree();
        }
    }

    private static final class EmptyFileTree extends AbstractFileTree {
        @Override
        public String getDisplayName() {
            return DEFAULT_TREE_DISPLAY_NAME;
        }

        @Override
        public Set<File> getFiles() {
            return Collections.emptySet();
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

//        @Override
//        public FileTree matching(Closure filterConfigClosure) {
//            return this;
//        }

        @Override
        public FileTree matching(Action<? super PatternFilterable> filterConfigAction) {
            return this;
        }

        @Override
        public FileTreeInternal matching(PatternFilterable patterns) {
            return this;
        }

        @Override
        public FileTree visit(FileVisitor visitor) {
            return this;
        }

        @Override
        public void visitContentsAsFileTrees(Consumer<FileTreeInternal> visitor) {
        }

        @Override
        protected void visitContents(FileCollectionStructureVisitor visitor) {
        }
    }

    private static final class FixedFileCollection extends AbstractOpaqueFileCollection {
        private final String displayName;
        private final ImmutableSet<File> files;

        public FixedFileCollection(String displayName, Factory<PatternSet> patternSetFactory, ImmutableSet<File> files) {
            super(patternSetFactory);
            this.displayName = displayName;
            this.files = files;
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }

        @Override
        protected Set<File> getIntrinsicFiles() {
            return files;
        }
    }

    private static class ResolvingFileCollection extends CompositeFileCollection {
        private final String displayName;
        private final PathToFileResolver resolver;
        private final Object source;
        private final ProviderResolutionStrategy providerResolutionStrategy;

        public ResolvingFileCollection(String displayName, PathToFileResolver resolver, Factory<PatternSet> patternSetFactory, ProviderResolutionStrategy providerResolutionStrategy, Object source) {
            super(patternSetFactory);
            this.displayName = displayName;
            this.resolver = resolver;
            this.source = source;
            this.providerResolutionStrategy = providerResolutionStrategy;
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }

        @Override
        protected void visitChildren(Consumer<FileCollectionInternal> visitor) {
            UnpackingVisitor nested = new UnpackingVisitor(visitor, resolver, patternSetFactory, providerResolutionStrategy, true);
            nested.add(source);
        }

        @Override
        protected void appendContents(TreeFormatter formatter) {
            formatter.node("source");
            formatter.startChildren();
            appendItem(formatter, source);
            formatter.endChildren();
        }

        private void appendItem(TreeFormatter formatter, Object item) {
            if (item instanceof FileCollectionInternal) {
                ((FileCollectionInternal) item).describeContents(formatter);
            } else if (item instanceof ArrayList) {
                for (Object child : (List) item) {
                    appendItem(formatter, child);
                }
            } else {
                formatter.node(item + " (class: " + item.getClass().getName() + ")");
            }
        }
    }

    private boolean isEmptyArray(Object sources) {
        return sources.getClass().isArray() && Array.getLength(sources) == 0;
    }
}