package com.tyron.builder.api.internal.file.collections;

import com.tyron.builder.api.file.FileTreeElement;
import com.tyron.builder.api.file.FileVisitDetails;
import com.tyron.builder.api.file.FileVisitor;
import com.tyron.builder.api.internal.file.FileTreeInternal;
import com.tyron.builder.api.tasks.util.PatternFilterable;
import com.tyron.builder.api.tasks.util.PatternSet;

import java.io.File;
import java.util.function.Predicate;

public class FilteredMinimalFileTree implements MinimalFileTree, FileSystemMirroringFileTree, PatternFilterableFileTree {
    private final PatternSet patterns;
    private final FileSystemMirroringFileTree tree;

    public FilteredMinimalFileTree(PatternSet patterns, FileSystemMirroringFileTree tree) {
        this.patterns = patterns;
        this.tree = tree;
    }

    @Override
    public String getDisplayName() {
        return tree.getDisplayName();
    }

    public FileSystemMirroringFileTree getTree() {
        return tree;
    }

    public PatternSet getPatterns() {
        return patterns;
    }

    @Override
    public DirectoryFileTree getMirror() {
        DirectoryFileTree mirror = tree.getMirror();
        return mirror.filter(this.patterns);
    }

    @Override
    public MinimalFileTree filter(PatternFilterable patterns) {
        PatternSet filter = this.patterns.intersect();
        filter.copyFrom(patterns);
        return new FilteredMinimalFileTree(filter, tree);
    }

    @Override
    public void visitStructure(MinimalFileTreeStructureVisitor visitor, FileTreeInternal owner) {
        tree.visitStructure(new MinimalFileTreeStructureVisitor() {
            @Override
            public void visitGenericFileTree(FileTreeInternal fileTree, FileSystemMirroringFileTree sourceTree) {
                visitor.visitGenericFileTree(owner, FilteredMinimalFileTree.this);
            }

            @Override
            public void visitFileTree(File root, PatternSet patterns, FileTreeInternal fileTree) {
                PatternSet intersect = patterns.intersect();
                intersect.copyFrom(FilteredMinimalFileTree.this.patterns);
                visitor.visitFileTree(root, intersect, owner);
            }

            @Override
            public void visitFileTreeBackedByFile(File file, FileTreeInternal fileTree, FileSystemMirroringFileTree sourceTree) {
                visitor.visitFileTreeBackedByFile(file, owner, FilteredMinimalFileTree.this);
            }
        }, owner);
    }

    @Override
    public void visit(FileVisitor visitor) {
        Predicate<FileTreeElement> spec = patterns.getAsSpec();
        tree.visit(new FileVisitor() {
            @Override
            public void visitDir(FileVisitDetails dirDetails) {
                if (spec.test(dirDetails)) {
                    visitor.visitDir(dirDetails);
                }
            }

            @Override
            public void visitFile(FileVisitDetails fileDetails) {
                if (spec.test(fileDetails)) {
                    visitor.visitFile(fileDetails);
                }
            }
        });
    }
}