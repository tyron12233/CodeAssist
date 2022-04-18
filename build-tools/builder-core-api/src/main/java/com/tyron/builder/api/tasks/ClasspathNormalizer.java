package com.tyron.builder.api.tasks;

/**
 * Normalizes file input that represents a Java runtime classpath.
 *
 * Compared to the default behavior this normalizer keeps the order of any root files,
 * but ignores the order and timestamps of files in directories and ZIP/JAR files.
 *
 * This normalization applies to not only files directly on the classpath, but also
 * to any ZIP/JAR files found inside directories or nested inside other ZIP/JAR files.
 *
 * @see org.gradle.api.tasks.Classpath
 *
 * @since 4.3
 */
public interface ClasspathNormalizer extends FileNormalizer {
}