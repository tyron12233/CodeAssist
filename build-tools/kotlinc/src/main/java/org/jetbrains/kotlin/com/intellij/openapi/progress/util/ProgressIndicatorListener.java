package org.jetbrains.kotlin.com.intellij.openapi.progress.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.kotlin.com.intellij.openapi.wm.ex.ProgressIndicatorEx;

import java.util.function.Consumer;

public interface ProgressIndicatorListener {
  default void cancelled() { }

  default void stopped() { }

  default void onFractionChanged(double fraction) { }

  default void installToProgressIfPossible(ProgressIndicator progress) {
    if (progress instanceof ProgressIndicatorEx) {
      installToProgress((ProgressIndicatorEx)progress);
    }
  }

  default void installToProgress(ProgressIndicatorEx progress) {
    progress.addStateDelegate(new AbstractProgressIndicatorExBase() {
      @Override
      public void cancel() {
        super.cancel();
        cancelled();
      }

      @Override
      public void stop() {
        super.stop();
        stopped();
      }

      @Override
      public void setFraction(double fraction) {
        super.setFraction(fraction);
        onFractionChanged(fraction);
      }
    });
  }

  static void whenProgressFractionChanged(@NotNull ProgressIndicator progress, @NotNull Consumer<? super Double> consumer) {
    ProgressIndicatorListener listener = new ProgressIndicatorListener() {
      @Override
      public void onFractionChanged(double fraction) {
        consumer.accept(fraction);
      }
    };
    listener.installToProgressIfPossible(progress);
  }
}