package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.util.ThreadLocalCachedValue;
import org.jetbrains.kotlin.com.intellij.openapi.util.ThrowableComputable;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.kotlin.com.intellij.util.SystemProperties;
import org.jetbrains.kotlin.com.intellij.util.text.ByteArrayCharSequence;

import java.io.DataOutputStream;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.IntFunction;

public final class IOUtil {
  public static final int KiB = 1024;
  public static final int MiB = 1024 * 1024;
  public static final int GiB = 1024 * 1024 * 1024;

  public static final ThreadLocal<Boolean> OVERRIDE_BYTE_BUFFERS_USE_NATIVE_BYTE_ORDER_PROP = new ThreadLocal<Boolean>() {
    @Override
    public void set(Boolean value) {
      if (get() != null) {
        throw new RuntimeException("Reentrant access");
      }
      super.set(value);
    }
  };

  public static final String SHARED_CACHES_PROP = "idea.shared.caches";

  /**
   * if false then storages will use {@link java.nio.ByteOrder#BIG_ENDIAN}
   */
  public static boolean useNativeByteOrderForByteBuffers() {
    Boolean forced = OVERRIDE_BYTE_BUFFERS_USE_NATIVE_BYTE_ORDER_PROP.get();
    return forced == null || forced.booleanValue();
  }

  public static boolean isSharedCachesEnabled() {
    return SystemProperties.getBooleanProperty(SHARED_CACHES_PROP, false);
  }

  private static final int STRING_HEADER_SIZE = 1;
  private static final int STRING_LENGTH_THRESHOLD = 255;
  private static final String LONGER_THAN_64K_MARKER = "LONGER_THAN_64K";

  private IOUtil() { }

  public static String readString(@NonNull DataInput stream) throws IOException {
    try {
      int length = stream.readInt();
        if (length == -1) {
            return null;
        }
        if (length == 0) {
            return "";
        }

      byte[] bytes = new byte[length * 2];
      stream.readFully(bytes);
      return new String(bytes, 0, length * 2, StandardCharsets.UTF_16BE);
    }
    catch (IOException e) {
      throw e;
    }
    catch (Throwable e) {
      throw new IOException(e);
    }
  }

  public static void writeString(@Nullable String s, @NonNull DataOutput stream) throws IOException {
    writeCharSequence(s, stream);
  }

  public static void writeCharSequence(@Nullable CharSequence s, @NonNull DataOutput stream) throws IOException {
    if (s == null) {
      stream.writeInt(-1);
      return;
    }

    stream.writeInt(s.length());
    if (s.length() == 0) {
      return;
    }

    byte[] bytes = new byte[s.length() * 2];

    for (int i = 0, i2 = 0; i < s.length(); i++, i2 += 2) {
      char aChar = s.charAt(i);
      bytes[i2] = (byte)(aChar >>> 8 & 0xFF);
      bytes[i2 + 1] = (byte)(aChar & 0xFF);
    }

    stream.write(bytes);
  }

  public static void writeUTFTruncated(@NonNull DataOutput stream, @NonNull String text) throws IOException {
    // we should not compare number of symbols to 65635 -> it is number of bytes what should be compared
    // ? 4 bytes per symbol - rough estimation
    if (text.length() > 16383) {
      stream.writeUTF(text.substring(0, 16383));
    }
    else {
      stream.writeUTF(text);
    }
  }

  private static final ThreadLocalCachedValue<byte[]> ourReadWriteBuffersCache = new ThreadLocalCachedValue<byte[]>() {
    @Override
    protected byte[] create() {
      return allocReadWriteUTFBuffer();
    }
  };

  public static void writeUTF(@NonNull DataOutput storage, @NonNull String value) throws IOException {
    writeUTFFast(ourReadWriteBuffersCache.getValue(), storage, value);
  }

  public static void writeUTF(@NonNull DataOutput storage, @NonNull CharSequence value) throws IOException {
    writeUTFFast(ourReadWriteBuffersCache.getValue(), storage, value);
  }

  public static String readUTF(@NonNull DataInput storage) throws IOException {
    return readUTFFast(ourReadWriteBuffersCache.getValue(), storage);
  }

  public static CharSequence readUTFCharSequence(@NonNull DataInput storage) throws IOException {
    return readUTFFastCharSequence(storage);
  }

  public static byte[] allocReadWriteUTFBuffer() {
    return new byte[STRING_LENGTH_THRESHOLD + STRING_HEADER_SIZE];
  }

  public static void writeUTFFast(byte[] buffer, @NonNull DataOutput storage, @NonNull String value) throws IOException {
    writeUTFFast(buffer, storage, (CharSequence)value);
  }

  public static void writeUTFFast(byte[] buffer, @NonNull DataOutput storage, @NonNull CharSequence value) throws IOException {
    int len = value.length();
    if (len < STRING_LENGTH_THRESHOLD) {
      buffer[0] = (byte)len;
      boolean isAscii = true;
      for (int i = 0; i < len; i++) {
        char c = value.charAt(i);
        if (c >= 128) {
          isAscii = false;
          break;
        }
        buffer[i + STRING_HEADER_SIZE] = (byte)c;
      }
      if (isAscii) {
        storage.write(buffer, 0, len + STRING_HEADER_SIZE);
        return;
      }
    }
    storage.writeByte((byte)0xFF);

    try {
      storage.writeUTF(value.toString());
    }
    catch (UTFDataFormatException e) {
      storage.writeUTF(LONGER_THAN_64K_MARKER);
      writeCharSequence(value, storage);
    }
  }

  public static String readUTFFast(byte[] buffer, @NonNull DataInput storage) throws IOException {
    int len = 0xFF & (int)storage.readByte();
    if (len == 0xFF) {
      return readLongString(storage);
    }

      if (len == 0) {
          return "";
      }
    storage.readFully(buffer, 0, len);
    return new String(buffer, 0, len, StandardCharsets.ISO_8859_1);
  }

  @Nullable
  private static String readLongString(@NonNull DataInput storage) throws IOException {
    String result = storage.readUTF();
    if (LONGER_THAN_64K_MARKER.equals(result)) {
      return readString(storage);
    }
    return result;
  }

  public static CharSequence readUTFFastCharSequence(@NonNull DataInput storage) throws IOException {
    int len = 0xFF & (int)storage.readByte();
    if (len == 0xFF) {
      return readLongString(storage);
    }

      if (len == 0) {
          return "";
      }
    byte[] data = new byte[len];
    storage.readFully(data, 0, len);
    return new ByteArrayCharSequence(data, 0, len);
  }

  public static boolean isAscii(@NonNull String str) {
    return isAscii((CharSequence)str);
  }

  public static boolean isAscii(@NonNull CharSequence str) {
    for (int i = 0, length = str.length(); i < length; ++i) {
        if (str.charAt(i) >= 128) {
            return false;
        }
    }
    return true;
  }

  public static boolean isAscii(char c) {
    return c < 128;
  }

  public static boolean deleteAllFilesStartingWith(@NonNull Path file) {
    String baseName = file.getFileName().toString();
    Path parentFile = file.getParent();
    if (parentFile == null) {
      return true;
    }

    List<Path> files = new ArrayList<>();
    try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(parentFile)) {
      for (Path path : directoryStream) {
        if (path.getFileName().toString().startsWith(baseName)) {
          files.add(path);
        }
      }
    }
    catch (NoSuchFileException ignore) {
      return true;
    }
    catch (IOException ignore) {
      return false;
    }

    boolean ok = true;
    for (Path f : files) {
      try {
        Files.deleteIfExists(f);
      }
      catch (IOException ignore) {
        ok = false;
      }
    }
    return ok;
  }

  public static boolean deleteAllFilesStartingWith(@NonNull File file) {
    final String baseName = file.getName();
    File parentFile = file.getParentFile();
    final File[] files = parentFile != null ? parentFile.listFiles(pathname -> pathname.getName().startsWith(baseName)) : null;

    boolean ok = true;
    if (files != null) {
      for (File f : files) {
        ok &= FileUtil.delete(f);
      }
    }

    return ok;
  }

  public static <T> T openCleanOrResetBroken(@NonNull ThrowableComputable<T, ? extends IOException> factoryComputable,
                                             @NonNull Path file) throws IOException {
    return openCleanOrResetBroken(factoryComputable, () -> deleteAllFilesStartingWith(file));
  }

  public static <T> T openCleanOrResetBroken(@NonNull ThrowableComputable<T, ? extends IOException> factoryComputable,
                                             @NonNull File file) throws IOException {
    return openCleanOrResetBroken(factoryComputable, () -> deleteAllFilesStartingWith(file));
  }

  public static <T> T openCleanOrResetBroken(@NonNull ThrowableComputable<T, ? extends IOException> factoryComputable,
                                             @NonNull Runnable cleanupCallback) throws IOException {
    try {
      return factoryComputable.compute();
    }
    catch (IOException ex) {
      cleanupCallback.run();
    }

    return factoryComputable.compute();
  }

  /**
   * Consider to use {@link com.intellij.util.io.externalizer.StringCollectionExternalizer}.
   */
  public static void writeStringList(@NonNull DataOutput out, @NonNull Collection<String> list) throws IOException {
    DataInputOutputUtil.writeINT(out, list.size());
    for (String s : list) {
      writeUTF(out, s);
    }
  }

  /**
   * Consider to use {@link com.intellij.util.io.externalizer.StringCollectionExternalizer}.
   */
  @NonNull
  public static <C extends Collection<String>> C readStringCollection(@NonNull DataInput in,
                                                                      @NonNull IntFunction<? extends C> collectionGenerator)
    throws IOException {
    int size = DataInputOutputUtil.readINT(in);
    C strings = collectionGenerator.apply(size);
    for (int i = 0; i < size; i++) {
      strings.add(readUTF(in));
    }
    return strings;
  }

  /**
   * Consider to use {@link com.intellij.util.io.externalizer.StringCollectionExternalizer}.
   */
  @NonNull
  public static List<String> readStringList(@NonNull DataInput in) throws IOException {
    return readStringCollection(in, ArrayList::new);
  }

  public static void closeSafe(@NonNull Logger log, Closeable... closeables) {
    for (Closeable closeable : closeables) {
      if (closeable != null) {
        try {
          closeable.close();
        }
        catch (IOException e) {
          log.error(e);
        }
      }
    }
  }


  public static <T> byte[] toBytes(final T object,
                                   final @NonNull DataExternalizer<? super T> externalizer) throws IOException {
    final ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (final DataOutputStream dos = new DataOutputStream(bos)) {
      externalizer.save(dos, object);
    }
    return bos.toByteArray();
  }

  public static <T> T fromBytes(final byte[] bytes,
                                final @NonNull DataExternalizer<? extends T> externalizer) throws IOException {
    final ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
    try (final DataInputStream dis = new DataInputStream(bis)) {
      return externalizer.read(dis);
    }
  }

  public static String toString(final @NonNull ByteBuffer buffer) {
    final byte[] bytes = new byte[buffer.capacity()];
    final ByteBuffer slice = buffer.duplicate();
    slice.position(0)
      .limit(buffer.capacity());
    slice.get(bytes);
    return Arrays.toString(bytes);
  }

  @NonNull
  public static String toHexString(final @NonNull ByteBuffer buffer) {
    return toHexString(buffer, /*pageSize: */ -1);
  }

  @NonNull
  public static String toHexString(final @NonNull ByteBuffer buffer,
                                   final int pageSize) {
    final byte[] bytes = new byte[buffer.capacity()];
    final ByteBuffer slice = buffer.duplicate();
    slice.position(0)
      .limit(buffer.capacity());
    slice.get(bytes);
    return toHexString(bytes, pageSize);
  }

  @NonNull
  public static String toHexString(final byte[] bytes) {
    return toHexString(bytes, /*pageSize: */-1);
  }

  @NonNull
  public static String toHexString(final byte[] bytes,
                                   final int pageSize) {
    final StringBuilder sb = new StringBuilder(bytes.length * 3);
    for (int i = 0; i < bytes.length; i++) {
      final byte b = bytes[i];
      final int unsignedByte = Byte.toUnsignedInt(b);
      if (unsignedByte < 16) {//Integer.toHexString format it single-digit, which ruins blocks alignment
        sb.append("0");
      }
      sb.append(Integer.toHexString(unsignedByte));
      if (pageSize > 0 && i % pageSize == pageSize - 1) {
        sb.append('\n');
      }
      else {
        sb.append(' ');
      }
    }
    return sb.toString();
  }
}