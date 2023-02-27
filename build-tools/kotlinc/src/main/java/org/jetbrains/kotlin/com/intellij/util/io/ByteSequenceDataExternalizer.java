package org.jetbrains.kotlin.com.intellij.util.io;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.ByteArraySequence;
import org.jetbrains.kotlin.com.intellij.util.ArrayUtil;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;

/**
 * @author Maxim.Mossienko
 */
public final class ByteSequenceDataExternalizer implements DataExternalizer<ByteArraySequence> {
  public static final ByteSequenceDataExternalizer INSTANCE = new ByteSequenceDataExternalizer();

  @Override
  public void save(@NotNull DataOutput out, ByteArraySequence value) throws IOException {
    out.write(value.getBytes(), value.getOffset(), value.getLength()); // todo fix double copying
  }

  @Override
  public ByteArraySequence read(@NotNull DataInput in) throws IOException {
    byte[] buf = new byte[((InputStream)in).available()]; // todo fix double copying
    in.readFully(buf);
    return new ByteArraySequence(buf);
  }
}