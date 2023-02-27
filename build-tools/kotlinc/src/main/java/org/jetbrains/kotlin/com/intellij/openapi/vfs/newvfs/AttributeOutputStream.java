package org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.util.io.ByteArraySequence;
import org.jetbrains.kotlin.com.intellij.util.io.DataOutputStream;
import org.jetbrains.kotlin.com.intellij.util.io.RepresentableAsByteArraySequence;

import java.io.IOException;

//@ApiStatus.Experimental
public abstract class AttributeOutputStream extends DataOutputStream implements RepresentableAsByteArraySequence {
  public <T extends DataOutputStream & RepresentableAsByteArraySequence> AttributeOutputStream(T out) {
    super(out);
  }

  abstract public void writeEnumeratedString(String str) throws IOException;

  @Override
  public @NonNull ByteArraySequence asByteArraySequence() {
    return ((RepresentableAsByteArraySequence)out).asByteArraySequence();
  }
}