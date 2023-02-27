package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class StreamUtil {
  private StreamUtil() { }

  /**
   * Buffers up to this size avoid native memory allocation in stream implementations.
   */
  public static final int BUFFER_SIZE = 8192;

  /**
   * Use {@link com.intellij.util.net.NetUtils#copyStreamContent NetUtils.copyStreamContent()} if you want a progress indicator.
   *
   * @param inputStream source stream
   * @param outputStream destination stream
   * @return bytes copied
   */
  public static int copy(@NonNull InputStream inputStream, @NonNull OutputStream outputStream) throws IOException {
    byte[] buffer = new byte[BUFFER_SIZE];
    int read;
    int total = 0;
    while ((read = inputStream.read(buffer)) > 0) {
      outputStream.write(buffer, 0, read);
      total += read;
    }
    return total;
  }

//  @ReviseWhenPortedToJDK(value = "9", description = "InputStream#readAllBytes")
  public static byte [] readBytes(@NonNull InputStream inputStream) throws IOException {
    UnsyncByteArrayOutputStream outputStream = new UnsyncByteArrayOutputStream();
    copy(inputStream, outputStream);
    return outputStream.toByteArray();
  }

  public static @NonNull String readText(@NonNull Reader reader) throws IOException {
    return readChars(reader).toString();
  }

  public static @NonNull String convertSeparators(@NonNull String s) {
    char[] source = s.toCharArray();
    char[] converted = convertSeparators(source);
    return converted == source ? s : new String(converted);
  }

  public static char[] readTextAndConvertSeparators(@NonNull Reader reader) throws IOException {
    CharArrayWriter chars = readChars(reader);
    return convertSeparators(chars.toCharArray());
  }

  private static char[] convertSeparators(char[] buffer) {
    int dst = 0;
    char prev = ' ';
    for (char c : buffer) {
      switch (c) {
        case'\r':
          buffer[dst++] = '\n';
          break;
        case'\n':
          if (prev != '\r') {
            buffer[dst++] = '\n';
          }
          break;
        default:
          buffer[dst++] = c;
          break;
      }
      prev = c;
    }

      if (dst == buffer.length) {
          return buffer;
      }
    return Arrays.copyOf(buffer, dst);
  }

  private static CharArrayWriter readChars(Reader reader) throws IOException {
    CharArrayWriter writer = new CharArrayWriter();
    char[] buffer = new char[2048];
    int read;
    while ((read = reader.read(buffer)) > 0) writer.write(buffer, 0, read);
    return writer;
  }

  //<editor-fold desc="Deprecated stuff.">
  /** @deprecated unfortunate name; please use {@link #copy(InputStream, OutputStream)} instead */
  @Deprecated
  public static int copyStreamContent(@NonNull InputStream inputStream, @NonNull OutputStream outputStream) throws IOException {
    return copy(inputStream, outputStream);
  }

  /** @deprecated bad style (resource closing should be caller's responsibility); use {@link #readBytes(InputStream)} instead */
  @Deprecated
  public static byte [] loadFromStream(@NonNull InputStream inputStream) throws IOException {
    UnsyncByteArrayOutputStream outputStream = new UnsyncByteArrayOutputStream();
    try {
      copy(inputStream, outputStream);
    }
    finally {
      inputStream.close();
    }
    return outputStream.toByteArray();
  }

  /** @deprecated bad style (resource closing should be caller's responsibility); use {@link #readText(Reader)} instead */
  @Deprecated
  public static @NonNull String readText(@NonNull InputStream inputStream) throws IOException {
    return readText(inputStream, StandardCharsets.UTF_8);
  }

  /** @deprecated bad style (resource closing should be caller's responsibility); use {@link #readText(Reader)} instead */
  @Deprecated
  public static @NonNull String readText(@NonNull InputStream inputStream, @NonNull String encoding) throws IOException {
    return readText(inputStream, Charset.forName(encoding));
  }

  /** @deprecated bad style (resource closing should be caller's responsibility); use {@link #readText(Reader)} instead */
  @Deprecated
  public static @NonNull String readText(@NonNull InputStream inputStream, @NonNull Charset encoding) throws IOException {
    byte[] data = loadFromStream(inputStream);
    return new String(data, encoding);
  }

  /** @deprecated unfortunate name; please use {@link #readText(Reader)} instead */
  @Deprecated
  public static @NonNull String readTextFrom(@NonNull Reader reader) throws IOException {
    return readText(reader);
  }

  /** @deprecated outdated pattern; use try-with-resources instead */
  @Deprecated
  @SuppressWarnings("DeprecatedIsStillUsed")
  public static void closeStream(@Nullable Closeable stream) {
    if (stream != null) {
      try {
        stream.close();
      }
      catch (IOException e) {
        Logger.getInstance(StreamUtil.class).error(e);
      }
    }
  }
  //</editor-fold>
}