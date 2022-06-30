package com.tyron.builder.internal;

import java.util.Collection;

public interface RelativePathSupplier {
    boolean isRoot();

    Collection<String> getSegments();

    String toRelativePath();
}