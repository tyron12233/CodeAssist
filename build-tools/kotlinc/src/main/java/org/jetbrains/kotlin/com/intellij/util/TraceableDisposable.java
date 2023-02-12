package org.jetbrains.kotlin.com.intellij.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Attachment;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.ExceptionWithAttachments;
import org.jetbrains.kotlin.com.intellij.openapi.util.objectTree.ThrowableInterner;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

/**
 * Traces creation and disposal by storing corresponding stack traces.
 * In constructor it saves creation stacktrace
 * In kill() it saves disposal stacktrace
 */
public class TraceableDisposable {
  private final Throwable CREATE_TRACE;
  private Throwable KILL_TRACE;

  public TraceableDisposable(boolean debug) {
    CREATE_TRACE = debug ? ThrowableInterner.intern(new Throwable()) : null;
  }

  public void kill(@Nullable String msg) {
    if (CREATE_TRACE != null) {
      KILL_TRACE = ThrowableInterner.intern(new Throwable(msg));
    }
  }

  public void killExceptionally(@NonNull Throwable throwable) {
    if (CREATE_TRACE != null) {
      KILL_TRACE = throwable;
    }
  }

  public void throwDisposalError(@NonNull String msg) throws RuntimeException {
    throw new DisposalException(msg);
  }

  private final class DisposalException extends RuntimeException implements ExceptionWithAttachments {
    private DisposalException(@NonNull String message) {
      super(message);
    }

    @Override
    public Attachment[] getAttachments() {
      List<Attachment> answer = new SmartList<>();
      if (CREATE_TRACE != null) {
        answer.add(new Attachment("creation", CREATE_TRACE));
      }
      if (KILL_TRACE != null) {
        answer.add(new Attachment("kill", KILL_TRACE));
      }
      return answer.toArray(Attachment.EMPTY_ARRAY);
    }
  }

  @NonNull
  public String getStackTrace() {
    StringWriter s = new StringWriter();
    PrintWriter out = new PrintWriter(s);
    if (CREATE_TRACE != null) {
      out.println("--------------Creation trace: ");
      CREATE_TRACE.printStackTrace(out);
    }
    if (KILL_TRACE != null) {
      out.println("--------------Kill trace: ");
      KILL_TRACE.printStackTrace(out);
    }
    out.println("-------------Own trace:");
    new DisposalException(String.valueOf(System.identityHashCode(this))).printStackTrace(out);
    out.flush();
    return s.toString();
  }
}