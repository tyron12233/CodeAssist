package org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.util.io.DataInputOutputUtil;
import org.jetbrains.kotlin.com.intellij.util.io.SimpleStringPersistentEnumerator;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

//@ApiStatus.Experimental
public final class AttributeInputStream extends DataInputStream {
  private final @NonNull SimpleStringPersistentEnumerator myStringEnumerator;

//  @ApiStatus.Internal
  public AttributeInputStream(@NonNull InputStream in, @NonNull SimpleStringPersistentEnumerator stringEnumerator) {
    super(in);
    myStringEnumerator = stringEnumerator;
  }

  /**
   * Read enumerated string from file's attribute. Might be used if one need to write many duplicated strings to attributes.
   */
  public String readEnumeratedString() throws IOException {
    return myStringEnumerator.valueOf(DataInputOutputUtil.readINT(this));
  }
}