package org.jetbrains.kotlin.com.intellij.openapi.vfs.encoding;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.util.Comparing;
import org.jetbrains.kotlin.com.intellij.openapi.util.ThrowableComputable;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

import java.nio.charset.Charset;

public abstract class EncodingRegistry {
  public abstract boolean isNative2Ascii(@NonNull VirtualFile virtualFile);
  public abstract boolean isNative2AsciiForPropertiesFiles();

  /**
   * @return charset configured in Settings|File Encodings|IDE encoding
   */
  @NonNull
  public abstract Charset getDefaultCharset();

  /**
   * @param virtualFile  file to get encoding for
   * @param useParentDefaults true to determine encoding from the parent
   * @return encoding configured for this file in Settings|File Encodings or,
   *         if useParentDefaults is true, encoding configured for nearest parent of virtualFile or,
   *         null if there is no configured encoding found.
   */
  @Nullable
  public abstract Charset getEncoding(@Nullable VirtualFile virtualFile, boolean useParentDefaults);

  public abstract void setEncoding(@Nullable VirtualFile virtualFileOrDir, @Nullable Charset charset);

  @Nullable
  public Charset getDefaultCharsetForPropertiesFiles(@Nullable VirtualFile virtualFile) {
    return null;
  }

  /**
   * @return encoding used by default in {@link com.intellij.execution.configurations.GeneralCommandLine}
   */
  public abstract @NonNull Charset getDefaultConsoleEncoding();

  public static EncodingRegistry getInstance() {
    return EncodingManager.getInstance();
  }

  public static <E extends Throwable> VirtualFile doActionAndRestoreEncoding(@NonNull VirtualFile fileBefore,
                                                                             @NonNull ThrowableComputable<? extends VirtualFile, E> action) throws E {
    EncodingRegistry registry = getInstance();
    Charset charsetBefore = registry.getEncoding(fileBefore, true);
    VirtualFile fileAfter = null;
    try {
      fileAfter = action.compute();
      return fileAfter;
    }
    finally {
      if (fileAfter != null) {
        Charset actual = registry.getEncoding(fileAfter, true);
        if (!Comparing.equal(actual, charsetBefore)) {
          registry.setEncoding(fileAfter, charsetBefore);
        }
      }
    }
  }
}