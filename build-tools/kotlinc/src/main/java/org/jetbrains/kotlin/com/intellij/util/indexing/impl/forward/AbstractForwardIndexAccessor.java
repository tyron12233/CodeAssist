package org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.util.ThreadLocalCachedByteArray;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.ByteArraySequence;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InputData;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InputDataDiffBuilder;
import org.jetbrains.kotlin.com.intellij.util.io.BufferExposingByteArrayOutputStream;
import org.jetbrains.kotlin.com.intellij.util.io.DataExternalizer;
import org.jetbrains.kotlin.com.intellij.util.io.UnsyncByteArrayInputStream;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public abstract class AbstractForwardIndexAccessor<Key, Value, DataType> implements ForwardIndexAccessor<Key, Value> {
  @NonNull
  private final DataExternalizer<DataType> myDataTypeExternalizer;

  public AbstractForwardIndexAccessor(@NonNull DataExternalizer<DataType> externalizer) {
    myDataTypeExternalizer = externalizer;
  }

  protected abstract InputDataDiffBuilder<Key, Value> createDiffBuilder(int inputId, @Nullable DataType inputData) throws IOException;

  @Nullable
  public DataType deserializeData(@Nullable ByteArraySequence sequence) throws IOException {
      if (sequence == null) {
          return null;
      }
    return deserializeFromByteSeq(sequence, myDataTypeExternalizer);
  }

  @NonNull
  @Override
  public InputDataDiffBuilder<Key, Value> getDiffBuilder(int inputId, @Nullable ByteArraySequence sequence) throws IOException {
    return createDiffBuilder(inputId, deserializeData(sequence));
  }

  @Nullable
  public abstract DataType convertToDataType(@NonNull InputData<Key, Value> data);

  @Nullable
  @Override
  public ByteArraySequence serializeIndexedData(@NonNull InputData<Key, Value> data) throws IOException {
    return serializeIndexedData(convertToDataType(data));
  }

  @Nullable
  public ByteArraySequence serializeIndexedData(@Nullable DataType data) throws IOException {
      if (data == null) {
          return null;
      }
    return serializeValueToByteSeq(data, myDataTypeExternalizer, getBufferInitialSize(data));
  }

  protected int getBufferInitialSize(@NonNull DataType dataType) {
    return 4;
  }

  private static final ThreadLocalCachedByteArray ourSpareByteArrayForKeys = new ThreadLocalCachedByteArray();
  private static final ThreadLocalCachedByteArray ourSpareByteArrayForValues = new ThreadLocalCachedByteArray();

  public static <Data> ByteArraySequence serializeKeyToByteSeq(/*must be not null if externalizer doesn't support nulls*/ Data data,
                                                            @NonNull DataExternalizer<Data> externalizer,
                                                            int bufferInitialSize) throws IOException {
    return serializeToByteSeq(data, externalizer, bufferInitialSize, ourSpareByteArrayForKeys);
  }

  public static <Data> ByteArraySequence serializeValueToByteSeq(/*must be not null if externalizer doesn't support nulls*/ Data data,
                                                            @NonNull DataExternalizer<Data> externalizer,
                                                            int bufferInitialSize) throws IOException {
    return serializeToByteSeq(data, externalizer, bufferInitialSize, ourSpareByteArrayForValues);
  }

  @Nullable
  public static <Data> ByteArraySequence serializeToByteSeq(Data data,
                                                             @NonNull DataExternalizer<Data> externalizer,
                                                             int bufferInitialSize,
                                                             @NonNull ThreadLocalCachedByteArray cachedBufferToUse) throws IOException {
    BufferExposingByteArrayOutputStream
            out = new BufferExposingByteArrayOutputStream(cachedBufferToUse::getBuffer, bufferInitialSize);
    DataOutputStream stream = new DataOutputStream(out);
    externalizer.save(stream, data);
    return out.size() == 0 ? null : out.toByteArraySequence();
  }

  public static <Data> Data deserializeFromByteSeq(@NonNull ByteArraySequence bytes,
                                                   @NonNull DataExternalizer<Data> externalizer) throws IOException {
    return externalizer.read(new DataInputStream(new UnsyncByteArrayInputStream(bytes.getBytes(), bytes.getOffset(), bytes.getLength())));
  }
}