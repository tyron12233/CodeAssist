package com.tyron.builder.api.internal.file.collections;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.file.DirectoryTree;
import com.tyron.builder.api.file.FileTreeElement;
import com.tyron.builder.api.file.FileVisitDetails;
import com.tyron.builder.api.file.FileVisitor;
import com.tyron.builder.api.file.RelativePath;
import com.tyron.builder.api.internal.file.DefaultFileVisitDetails;
import com.tyron.builder.api.internal.file.FileCollectionStructureVisitor;
import com.tyron.builder.api.internal.file.FileTreeInternal;
import com.tyron.builder.internal.file.pattern.PatternStep;
import com.tyron.builder.internal.file.pattern.PatternStepFactory;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.internal.nativeintegration.services.FileSystems;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.util.Predicates;

import java.io.File;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Directory walker that supports a single Ant-style include pattern
 * and an optional exclude spec. Efficient in the sense that it will only
 * exhaustively scan a directory hierarchy if, and from the point where,
 * a '**' pattern is encountered.
 */
public class SingleIncludePatternFileTree implements MinimalFileTree, LocalFileTree, DirectoryTree {
    private final File baseDir;
    private final String includePattern;
    private final List<String> patternSegments;
    private final Predicate<FileTreeElement> excludeSpec;
    private final FileSystem fileSystem = FileSystems.getDefault();

    public SingleIncludePatternFileTree(File baseDir, String includePattern) {
        this(baseDir, includePattern, Predicates.satisfyNone());
    }

    public SingleIncludePatternFileTree(File baseDir, String includePattern, Predicate<FileTreeElement> excludeSpec) {
        this.baseDir = baseDir;
        if (includePattern.endsWith("/") || includePattern.endsWith("\\")) {
            includePattern += "**";
        }
        this.includePattern = includePattern;
        this.patternSegments = Arrays.asList(includePattern.split("[/\\\\]"));
        this.excludeSpec = excludeSpec;
    }

    @Override
    public File getDir() {
        return baseDir;
    }

    @Override
    public PatternSet getPatterns() {
        return new PatternSet().include(includePattern).exclude(excludeSpec);
    }

    @Override
    public void visitStructure(FileCollectionStructureVisitor visitor, FileTreeInternal owner) {
        visitor.visitFileTree(baseDir, getPatterns(), owner);
    }

    @Override
    public void visit(FileVisitor visitor) {
        doVisit(visitor, baseDir, new LinkedList<>(), 0, new AtomicBoolean());
    }

    private void doVisit(FileVisitor visitor, File file, LinkedList<String> relativePath, int segmentIndex, AtomicBoolean stopFlag) {
        if (stopFlag.get()) {
            return;
        }

        String segment = patternSegments.get(segmentIndex);

        if (segment.contains("**")) {
            PatternSet patternSet = new PatternSet();
            patternSet.include(includePattern);
            patternSet.exclude(excludeSpec);
            DirectoryFileTree fileTree = new DirectoryFileTree(baseDir, patternSet, fileSystem);
            fileTree.visitFrom(visitor, file, new RelativePath(file.isFile(), relativePath.toArray(new String[relativePath.size()])));
        } else if (segment.contains("*") || segment.contains("?")) {
            PatternStep step = PatternStepFactory.getStep(segment, false);
            File[] children = file.listFiles();
            if (children == null) {
                if (!file.canRead()) {
                    throw new BuildException(String.format("Could not list contents of directory '%s' as it is not readable.", file));
                }
                // else, might be a link which points to nothing, or has been removed while we're visiting, or ...
                throw new BuildException(String.format("Could not list contents of '%s'.", file));
            }
            for (File child : children) {
                if (stopFlag.get()) {
                    break;
                }
                String childName = child.getName();
                if (step.matches(childName)) {
                    relativePath.addLast(childName);
                    doVisitDirOrFile(visitor, child, relativePath, segmentIndex + 1, stopFlag);
                    relativePath.removeLast();
                }
            }
        } else {
            relativePath.addLast(segment);
            doVisitDirOrFile(visitor, new File(file, segment), relativePath, segmentIndex + 1, stopFlag);
            relativePath.removeLast();
        }
    }

    private void doVisitDirOrFile(FileVisitor visitor, File file, LinkedList<String> relativePath, int segmentIndex, AtomicBoolean stopFlag) {
        if (file.isFile()) {
            if (segmentIndex == patternSegments.size()) {
                RelativePath path = new RelativePath(true, relativePath.toArray(new String[relativePath.size()]));
                FileVisitDetails
                        details = new DefaultFileVisitDetails(file, path, stopFlag, fileSystem, fileSystem);
                if (!excludeSpec.test(details)) {
                    visitor.visitFile(details);
                }
            }
        } else if (file.isDirectory()) {
            RelativePath path = new RelativePath(false, relativePath.toArray(new String[relativePath.size()]));
            FileVisitDetails details = new DefaultFileVisitDetails(file, path, stopFlag, fileSystem, fileSystem);
            if (!excludeSpec.test(details)) {
                visitor.visitDir(details);
            }
            if (segmentIndex < patternSegments.size()) {
                doVisit(visitor, file, relativePath, segmentIndex, stopFlag);
            }
        }
    }

    @Override
    public String getDisplayName() {
        return "directory '" + baseDir + "' include '" + includePattern + "'";
    }
}