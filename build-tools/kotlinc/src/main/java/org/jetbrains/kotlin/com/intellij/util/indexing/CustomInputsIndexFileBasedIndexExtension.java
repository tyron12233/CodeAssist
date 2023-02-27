package org.jetbrains.kotlin.com.intellij.util.indexing;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.util.io.DataExternalizer;

import java.util.Collection;

public interface CustomInputsIndexFileBasedIndexExtension<K> {
  @NonNull
  DataExternalizer<Collection<K>> createExternalizer();
}