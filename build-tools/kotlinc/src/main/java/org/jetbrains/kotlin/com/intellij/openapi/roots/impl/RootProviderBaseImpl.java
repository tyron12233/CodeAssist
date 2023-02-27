package org.jetbrains.kotlin.com.intellij.openapi.roots.impl;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.roots.RootProvider;
import org.jetbrains.kotlin.com.intellij.util.EventDispatcher;

@ApiStatus.Internal
public abstract class RootProviderBaseImpl implements RootProvider {
  protected final EventDispatcher<RootSetChangedListener> myDispatcher = EventDispatcher.create(RootSetChangedListener.class);
  @Override
  public void addRootSetChangedListener(@NotNull RootSetChangedListener listener) {
    myDispatcher.addListener(listener);
  }

  @Override
  public void removeRootSetChangedListener(@NotNull RootSetChangedListener listener) {
    myDispatcher.removeListener(listener);
  }

  @Override
  public void addRootSetChangedListener(@NotNull RootSetChangedListener listener, @NotNull Disposable parentDisposable) {
    myDispatcher.addListener(listener);
  }

  protected void fireRootSetChanged() {
    myDispatcher.getMulticaster().rootSetChanged(this);
  }
}