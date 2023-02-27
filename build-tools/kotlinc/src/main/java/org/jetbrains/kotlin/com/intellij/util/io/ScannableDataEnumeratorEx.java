package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.util.Processor;

import java.io.IOException;

/**
 * Interface for enumerators that allow scanning through all their entries.
 */
public interface ScannableDataEnumeratorEx<Data> extends DataEnumeratorEx<Data> {

  /**
   * @return true if all available entries were processed, false if scanning was stopped earlier
   * by processor returning false
   */
  boolean processAllDataObjects(@NonNull Processor<? super Data> processor) throws IOException;
}