package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.util.io.DataExternalizer;
import org.jetbrains.kotlin.com.intellij.util.io.VoidDataExternalizer;

/**
 * A specialization of FileBasedIndexExtension allowing to create a mapping {@code [DataObject -> List of files containing this object]}.
 */
@ApiStatus.OverrideOnly
public abstract class ScalarIndexExtension<K> extends FileBasedIndexExtension<K, Void> {

  /**
   * To remove in IDEA 2018.1.
   *
   * @deprecated use {@link VoidDataExternalizer#INSTANCE}
   */
  @Deprecated(forRemoval = true)
  public static final DataExternalizer<Void> VOID_DATA_EXTERNALIZER = VoidDataExternalizer.INSTANCE;

  @NotNull
  @Override
  public final DataExternalizer<Void> getValueExternalizer() {
    return VoidDataExternalizer.INSTANCE;
  }
}