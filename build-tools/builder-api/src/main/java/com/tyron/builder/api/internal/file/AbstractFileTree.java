package com.tyron.builder.api.internal.file;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.file.EmptyFileVisitor;
import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.file.FileVisitDetails;
import com.tyron.builder.api.file.FileVisitor;
import com.tyron.builder.api.internal.Cast;
import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.internal.MutableBoolean;
import com.tyron.builder.api.tasks.util.PatternFilterable;
import com.tyron.builder.api.tasks.util.PatternSet;

public abstract class AbstractFileTree extends AbstractFileCollection implements FileTreeInternal {
    public AbstractFileTree() {
        super();
    }

    public AbstractFileTree(Factory<PatternSet> patternSetFactory) {
        super(patternSetFactory);
    }

    @Override
    public Set<File> getFiles() {
        final Set<File> files = new LinkedHashSet<File>();
        visit(new EmptyFileVisitor() {
            @Override
            public void visitFile(FileVisitDetails fileDetails) {
                files.add(fileDetails.getFile());
            }
        });
        return files;
    }

    @Override
    public boolean isEmpty() {
        final MutableBoolean found = new MutableBoolean();
        visit(new EmptyFileVisitor() {
            @Override
            public void visitFile(FileVisitDetails fileDetails) {
                found.set(true);
                fileDetails.stopVisiting();
            }
        });
        return !found.get();
    }

//    @Override
//    public FileTree matching(Closure filterConfigClosure) {
//        return matching(configure(filterConfigClosure, patternSetFactory.create()));
//    }

    @Override
    public FileTree matching(Action<? super PatternFilterable> filterConfigAction) {
        PatternSet patternSet = patternSetFactory.create();
        filterConfigAction.execute(patternSet);
        return matching(patternSet);
    }

    public Map<String, File> getAsMap() {
        final Map<String, File> map = new LinkedHashMap<String, File>();
        visit(new EmptyFileVisitor() {
            @Override
            public void visitFile(FileVisitDetails fileDetails) {
                map.put(fileDetails.getRelativePath().getPathString(), fileDetails.getFile());
            }
        });
        return map;
    }

//    @Override
//    protected void addAsResourceCollection(Object builder, String nodeName) {
//        new AntFileTreeBuilder(getAsMap()).addToAntBuilder(builder, nodeName);
//    }

    @Override
    public FileTreeInternal getAsFileTree() {
        return this;
    }

    @Override
    public FileTree plus(FileTree fileTree) {
        return new UnionFileTree(this, Cast.cast(FileTreeInternal.class, fileTree));
    }

//    @Override
//    public FileTree visit(Closure closure) {
//        return visit(fileVisitorFrom(closure));
//    }

//    static FileVisitor fileVisitorFrom(Closure closure) {
//        return DefaultGroovyMethods.asType(closure, FileVisitor.class);
//    }

    @Override
    public FileTree visit(final Action<? super FileVisitDetails> visitor) {
        return visit(new FileVisitor() {
            @Override
            public void visitDir(FileVisitDetails dirDetails) {
                visitor.execute(dirDetails);
            }

            @Override
            public void visitFile(FileVisitDetails fileDetails) {
                visitor.execute(fileDetails);
            }
        });
    }
}