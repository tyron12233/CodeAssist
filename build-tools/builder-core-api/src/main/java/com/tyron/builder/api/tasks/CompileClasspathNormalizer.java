package com.tyron.builder.api.tasks;

/**
 * Normalizes file input that represents a Java compile classpath.
 *
 * Compared to the default behavior this normalizer keeps the order of any root files,
 * but ignores the order and timestamps of files in directories and ZIP/JAR files.
 * Compared to {@link ClasspathNormalizer} this normalizer only snapshots the ABIs of class files,
 * and ignores any non-class resource.
 *
 * @see org.gradle.api.tasks.CompileClasspath
 *
 * @since 4.3
 */
public interface CompileClasspathNormalizer extends FileNormalizer {
}