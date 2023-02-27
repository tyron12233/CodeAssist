package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.Forceable;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.util.ThrowableNotNullFunction;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtilRt;
import org.jetbrains.kotlin.com.intellij.util.ExceptionUtil;
import org.jetbrains.kotlin.com.intellij.util.SmartList;
import org.jetbrains.kotlin.com.intellij.util.SystemProperties;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.lang.CompoundRuntimeException;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public class ResizeableMappedFile implements Forceable, Closeable {
  private static final Logger LOG = Logger.getInstance(ResizeableMappedFile.class);

  private static final boolean truncateOnClose = SystemProperties.getBooleanProperty("idea.resizeable.file.truncate.on.close", false);
  private volatile long myLogicalSize;
  private volatile long myLastWrittenLogicalSize;
  private final PagedFileStorage myStorage;
  private final int myInitialSize;

  static final int DEFAULT_ALLOCATION_ROUND_FACTOR = 4096;
  private int myRoundFactor = DEFAULT_ALLOCATION_ROUND_FACTOR;

  public ResizeableMappedFile(@NonNull Path file, int initialSize, @Nullable StorageLockContext lockContext, int pageSize,
                              boolean valuesAreBufferAligned) throws IOException {
    this(file, initialSize, lockContext, pageSize, valuesAreBufferAligned, false);
  }

  public ResizeableMappedFile(@NonNull Path file,
                              int initialSize,
                              @Nullable StorageLockContext lockContext,
                              int pageSize,
                              boolean valuesAreBufferAligned,
                              boolean nativeBytesOrder) throws IOException {
    myStorage = new PagedFileStorage(file, lockContext, pageSize, valuesAreBufferAligned, nativeBytesOrder);
    ensureParentDirectoryExists();
    myInitialSize = initialSize;
    myLastWrittenLogicalSize = myLogicalSize = readLength();
  }

  public boolean isNativeBytesOrder() {
    return myStorage.isNativeBytesOrder();
  }

  public void clear() throws IOException {
    myStorage.resize(0);
    myLogicalSize = 0;
    myLastWrittenLogicalSize = 0;
  }

  public long length() {
    return myLogicalSize;
  }

  private long realSize() {
    return myStorage.length();
  }

  void ensureSize(final long pos) {
    myLogicalSize = Math.max(pos, myLogicalSize);
    expand(pos);
  }

  public void setRoundFactor(int roundFactor) {
    myRoundFactor = roundFactor;
  }

  private void expand(final long max) {
    long realSize = realSize();
      if (max <= realSize) {
          return;
      }
    long suggestedSize;

    if (realSize == 0) {
      suggestedSize = doRoundToFactor(Math.max(myInitialSize, max));
    } else {
      suggestedSize = Math.max(realSize + 1, 2); // suggestedSize should increase with int multiplication on 1.625 factor

      while (max > suggestedSize) {
        long newSuggestedSize = suggestedSize * 13 >> 3;
        if (newSuggestedSize >= Integer.MAX_VALUE) {
          suggestedSize += suggestedSize / 5;
        }
        else {
          suggestedSize = newSuggestedSize;
        }
      }

      suggestedSize = doRoundToFactor(suggestedSize);
    }

    try {
      myStorage.resize(suggestedSize);
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private long doRoundToFactor(long suggestedSize) {
    int roundFactor = myRoundFactor;
    if (suggestedSize % roundFactor != 0) {
      suggestedSize = (suggestedSize / roundFactor + 1) * roundFactor;
    }
    return suggestedSize;
  }

  private Path getLengthFile() {
    Path file = myStorage.getFile();
    return file.resolveSibling(file.getFileName() + ".len");
  }

  private void writeLength(final long len) {
    final Path lengthFile = getLengthFile();
    try (DataOutputStream stream = FileUtilRt.doIOOperation(lastAttempt -> {
      try {
        return new DataOutputStream(Files.newOutputStream(lengthFile));
      }
      catch (NoSuchFileException ex) {
        ensureParentDirectoryExists();
          if (!lastAttempt) {
              return null;
          }
        throw ex;
      }
    })) {
      if (stream != null) {
        stream.writeLong(len);
      }
    }
    catch (IOException e) {
      LOG.error(e);
    }
  }

  public boolean isDirty() {
    return myStorage.isDirty();
  }

  @Override
  public void force()  {
    ensureLengthWritten();
    myStorage.force();
  }

  private void ensureLengthWritten() {
    if (myLastWrittenLogicalSize != myLogicalSize) {
      writeLength(myLogicalSize);
      myLastWrittenLogicalSize = myLogicalSize;
    }
  }

  private void ensureParentDirectoryExists() throws IOException {
    Path parent = getLengthFile().getParent();
    if (!Files.exists(parent)) {
      Files.createDirectories(parent);
    }
  }

  private long readLength() throws IOException {
    Path lengthFile = getLengthFile();
    long zero = 0L;
    if (!Files.exists(lengthFile) && (!Files.exists(myStorage.getFile()) || Files.size(myStorage.getFile()) == zero)) {
      writeLength(zero);
      return zero;
    }

    try (DataInputStream stream = new DataInputStream(Files.newInputStream(lengthFile, StandardOpenOption.READ))) {
      return stream.readLong();
    }
    catch (IOException e) {
      long realSize = realSize();
      writeLength(realSize);
      LOG.error("storage size = " + realSize + ", file size = " + Files.size(myStorage.getFile()), e);
      return realSize;
    }
  }

  public int getInt(long index) throws IOException {
    return myStorage.getInt(index);
  }

  public void putInt(long index, int value) throws IOException {
    ensureSize(index + 4);
    myStorage.putInt(index, value);
  }

  public long getLong(long index) throws IOException {
    return myStorage.getLong(index);
  }

  public void putLong(long index, long value) throws IOException {
    ensureSize(index + 8);
    myStorage.putLong(index, value);
  }

  public byte get(long index) throws IOException {
    return get(index, true);
  }

  public byte get(long index, boolean checkAccess) throws IOException {
    return myStorage.get(index, checkAccess);
  }

  public void get(long index, byte[] dst, int offset, int length, boolean checkAccess) throws IOException {
    myStorage.get(index, dst, offset, length, checkAccess);
  }

  public void put(long index, byte[] src, int offset, int length) throws IOException {
    ensureSize(index + length);
    myStorage.put(index, src, offset, length);
  }

  public void put(long index, @NonNull ByteBuffer buffer) throws IOException {
    ensureSize(index + (buffer.limit() - buffer.position()));
    myStorage.putBuffer(index, buffer);
  }

  public void setLogicalSize(long logicalSize) {
    myLogicalSize = logicalSize;
  }

  public long getLogicalSize() {
    return myLogicalSize;
  }

  public void close() throws IOException {
    List<Exception> exceptions = new SmartList<>();
    try {
      ensureLengthWritten();
      assert myLogicalSize == myLastWrittenLogicalSize;
      myStorage.force();
      if (truncateOnClose && myLogicalSize < myStorage.length()) {
        myStorage.resize(myLogicalSize);
      }
    } catch (Exception e) {
      exceptions.add(e);
    }
    try {
      myStorage.close();
    } catch (Exception e) {
      exceptions.add(e);
    }
    if (!exceptions.isEmpty()) {
      throw new IOException(new CompoundRuntimeException(exceptions));
    }
  }

  @NonNull
  public PagedFileStorage getPagedFileStorage() {
    return myStorage;
  }

  @NonNull
  public StorageLockContext getStorageLockContext() {
    return myStorage.getStorageLockContext();
  }

  public <R> R readInputStream(@NonNull ThrowableNotNullFunction<? super InputStream, R, ? extends IOException> consumer)
    throws IOException {
    return myStorage.readInputStream(consumer);
  }

  public <R> R readChannel(@NonNull ThrowableNotNullFunction<? super ReadableByteChannel, R, ? extends IOException> consumer)
    throws IOException {
    return myStorage.readChannel(consumer);
  }

  public void lockRead() {
    myStorage.lockRead();
  }

  public void unlockRead() {
    myStorage.unlockRead();
  }

  public void lockWrite() {
    myStorage.lockWrite();
  }

  public void unlockWrite() {
    myStorage.unlockWrite();
  }

  @Override
  public String toString() {
    return "ResizeableMappedFile[" + myStorage.toString() + "]";
  }
}