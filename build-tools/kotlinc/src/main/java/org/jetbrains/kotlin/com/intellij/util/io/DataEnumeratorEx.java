package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.Nullable;

import java.io.IOException;

public interface DataEnumeratorEx<Data> extends DataEnumerator<Data> {
  /**
   * id=0 used as NULL (i.e. absent) value
   */
  int NULL_ID = 0;

  /**
   * @return id of the value, if value is already known to the enumerator,
   * or NULL_ID, if value is not known yet
   */
  int tryEnumerate(@Nullable Data value) throws IOException;
}