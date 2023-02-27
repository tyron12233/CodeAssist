package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.components.Service;
import org.jetbrains.kotlin.com.intellij.util.containers.RecentStringInterner;

@Service
final class StubStringInterner {
  @NonNull
  private final RecentStringInterner myStringInterner;

  static StubStringInterner getInstance() {
    return ApplicationManager.getApplication().getService(StubStringInterner.class);
  }

  StubStringInterner() {
    myStringInterner = new RecentStringInterner(ApplicationManager.getApplication());
  }

  @Nullable
//  @Contract("null -> null")
  String intern(@Nullable String str) {
      if (str == null) {
          return null;
      }
    return myStringInterner.get(str);
  }
}