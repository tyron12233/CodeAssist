package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class VoidDataExternalizer implements DataExternalizer<Void> {
  public static final VoidDataExternalizer INSTANCE = new VoidDataExternalizer();

  @Override
  public void save(@NonNull final DataOutput out, final Void value) throws IOException {
  }

  @Override
  @Nullable
  public Void read(@NonNull final DataInput in) throws IOException {
    return null;
  }
}