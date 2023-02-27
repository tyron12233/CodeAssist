package org.jetbrains.kotlin.com.intellij.util.indexing;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.project.IndexNotReadyException;
import org.jetbrains.kotlin.com.intellij.openapi.util.ThrowableComputable;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndex;

/**
 * Represents special index access types in dumb mode.
 * <p>
 * Usually, index access in dumb mode is prohibited and the only possible result is a {@link IndexNotReadyException}.
 * In some case however, it might be useful to access incomplete or out-dated indexes while the indexing is in progress.
 * <p>
 * Index access in dumb mode can be done using any of {@link DumbModeAccessType}s and
 * {@link DumbModeAccessType#ignoreDumbMode(ThrowableComputable)} or
 * {@link DumbModeAccessType#ignoreDumbMode(Runnable)}.
 * {@link DumbModeAccessType} controls which kind of data will be returned from the index.
 */
public enum DumbModeAccessType {
  /**
   * If the index is accessed with {@code RELIABLE_DATA_ONLY}, then only up-to-date indexed data will be returned as a
   * result of the index query.
   */
  RELIABLE_DATA_ONLY,
  /**
   * If the index is accessed with {@code RAW_INDEX_DATA_ACCEPTABLE}, then any (even invalid) data currently present in
   * the index will be returned as a result of the index query.
   * <p>
   * Note, this type doesn't work for {@link StubIndex}.
   */
  RAW_INDEX_DATA_ACCEPTABLE;

  /**
   * Executes a command with a requested access type and allows it to have index access in dumb mode.
   * Inside the command it's safe to call index related stuff and
   * {@link IndexNotReadyException} are not expected to happen here.
   *
   * <p> In smart mode, the behavior is similar to direct command execution.
   * @param command - A command to execute
   */
  public void ignoreDumbMode(@NonNull Runnable command) {
    FileBasedIndex.getInstance().ignoreDumbMode(this, command);
  }

  /**
   * Executes command with a requested access type and allows it to have index access in dumb mode.
   * Inside the command it's safe to call index related stuff and
   * {@link IndexNotReadyException} are not expected to happen here.
   *
   * <p> In smart mode, the behavior is similar to direct command execution.
   * @param computable - A command to execute
   */
  public <T, E extends Throwable> T ignoreDumbMode(@NonNull ThrowableComputable<T, E> computable) throws E {
    return FileBasedIndex.getInstance().ignoreDumbMode(this, computable);
  }
}