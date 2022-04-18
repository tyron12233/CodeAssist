package com.tyron.builder.internal.classpath;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * An immutable classpath.
 */
public interface ClassPath {

    ClassPath EMPTY = new DefaultClassPath();

    boolean isEmpty();

    List<URI> getAsURIs();

    List<File> getAsFiles();

    List<URL> getAsURLs();

    URL[] getAsURLArray();

    ClassPath plus(Collection<File> classPath);

    ClassPath plus(ClassPath classPath);

    ClassPath removeIf(Predicate<? super File> filter);
}