package org.jetbrains.kotlin.com.intellij.util.io.keyStorage;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.util.io.InlineKeyDescriptor;

import java.io.IOException;

public class InlinedKeyStorage<Data> implements AppendableObjectStorage<Data> {

  private final InlineKeyDescriptor<Data> myDescriptor;

  public InlinedKeyStorage(@NonNull InlineKeyDescriptor<Data> descriptor) {
    myDescriptor = descriptor;
  }

  @Override
  public Data read(int addr, boolean checkAccess) throws IOException {
    return myDescriptor.fromInt(addr);
  }

  @Override
  public boolean processAll(@NonNull StorageObjectProcessor<? super Data> processor) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public int append(Data value) throws IOException {
    return myDescriptor.toInt(value);
  }

  @Override
  public boolean checkBytesAreTheSame(int addr, Data value) {
    return false;
  }

  @Override
  public void clear() throws IOException {
    //do nothing
  }

  @Override
  public void lockRead() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void unlockRead() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void lockWrite() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void unlockWrite() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getCurrentLength() {
    return -1;
  }

  @Override
  public boolean isDirty() {
    return false;
  }

  @Override
  public void force() {

  }

  @Override
  public void close() throws IOException {

  }
}