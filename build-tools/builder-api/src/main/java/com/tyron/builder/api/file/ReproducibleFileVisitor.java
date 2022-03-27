package com.tyron.builder.api.file;


/**
 * Visitor which can request a reproducible file order.
 *
 * @since 3.4
 */
public interface ReproducibleFileVisitor extends FileVisitor {
    /**
     * Whether the {@link FileVisitor} should receive the files in a reproducible order independent of the underlying file system.
     *
     * @return <code>true</code> if files should be walked in a reproducible order.
     */
    boolean isReproducibleFileOrder();
}