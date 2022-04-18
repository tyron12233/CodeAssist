package com.tyron.builder.api.file;

/**
 * The EmptyFileVisitor can be extends by implementations that only require to implement one of the 2 visit methods
 * (dir or file). This is just to limit the amount of code clutter when not both visit methods need to be implemented.
 */
public class EmptyFileVisitor implements FileVisitor {

    @Override
    public void visitDir(FileVisitDetails dirDetails) { }

    @Override
    public void visitFile(FileVisitDetails fileDetails) { }
}