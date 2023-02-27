package org.jetbrains.kotlin.com.intellij.util.io.pagecache.impl;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;

@FunctionalInterface
public interface PageContentLoader {
  @NonNull
  ByteBuffer loadPageContent(final @NonNull PageImpl page) throws IOException;
}