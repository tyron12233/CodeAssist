package org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.util.io.DataInputOutputUtil;
import org.jetbrains.kotlin.com.intellij.util.io.DataOutputStream;
import org.jetbrains.kotlin.com.intellij.util.io.RepresentableAsByteArraySequence;
import org.jetbrains.kotlin.com.intellij.util.io.SimpleStringPersistentEnumerator;

import java.io.IOException;

@ApiStatus.Experimental
public final class AttributeOutputStreamBase extends AttributeOutputStream {
  private final @NotNull SimpleStringPersistentEnumerator myStringEnumerator;

  @ApiStatus.Internal
  public <T extends DataOutputStream & RepresentableAsByteArraySequence> AttributeOutputStreamBase(@NotNull T out,
                                                                                                   @NotNull SimpleStringPersistentEnumerator stringEnumerator) {
    super(out);
    myStringEnumerator = stringEnumerator;
  }

  /**
   * Enumerate & write string to file's attribute. Might be used if one need to write many duplicated strings to attributes.
   */
  @Override
  public void writeEnumeratedString(String str) throws IOException {
    DataInputOutputUtil.writeINT(this, myStringEnumerator.enumerate(str));
  }
}