package org.jetbrains.kotlin.com.intellij.openapi.roots.impl;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderRootType;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFilePointerContainer;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFilePointerManagerEx;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.VirtualFilePointerContainerImpl;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.pointers.VirtualFilePointerManager;
import org.jetbrains.kotlin.com.intellij.util.ArrayUtilRt;
import org.jetbrains.kotlin.com.intellij.util.ConcurrencyUtil;
import org.jetbrains.kotlin.com.intellij.util.ObjectUtils;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class OrderRootsCache {
  private final AtomicReference<ConcurrentMap<CacheKey, VirtualFilePointerContainer>> myRoots = new AtomicReference<>();
  private final Disposable myParentDisposable;
  private Disposable myRootsDisposable; // accessed in EDT

  public OrderRootsCache(@NonNull Disposable parentDisposable) {
    myParentDisposable = parentDisposable;
    disposePointers();
  }

  private void disposePointers() {
    if (myRootsDisposable != null) {
      Disposer.dispose(myRootsDisposable);
    }
    if (Disposer.getDisposalTrace(myParentDisposable) != null) {
      Disposer.register(myParentDisposable, myRootsDisposable = Disposer.newDisposable());
    }
  }

  private static final VirtualFilePointerContainer EMPTY = sentinel("Empty Vfs", VirtualFilePointerContainer.class);

  /**
   * Creates an instance of class {@code ofInterface} with its {@link Object#toString()} method returning {@code name}.
   * No other guarantees about return value behaviour.
   * {@code ofInterface} must represent an interface class.
   * Useful for stubs in generic code, e.g. for storing in {@code List<T>} to represent empty special value.
   */
  public static @NonNull <T> T sentinel(@NonNull String name, @NonNull Class<T> ofInterface) {
    if (!ofInterface.isInterface()) {
      throw new IllegalArgumentException("Expected interface but got: " + ofInterface);
    }
    // java.lang.reflect.Proxy.ProxyClassFactory fails if the class is not available via the classloader.
    // We must use interface own classloader because classes from plugins are not available via ObjectUtils' classloader.
    //noinspection unchecked
    return (T) Proxy.newProxyInstance(ofInterface.getClassLoader(), new Class[]{ofInterface}, (__, method, args) -> {
      if ("toString".equals(method.getName()) && args.length == 0) {
        return name;
      }
      throw new AbstractMethodError();
    });
  }

  private VirtualFilePointerContainer createContainer(@NonNull Collection<String> urls) {
    // optimization: avoid creating heavy container for empty list, use 'EMPTY' stub for that case
    VirtualFilePointerContainer container;
    if (urls.isEmpty()) {
      container = EMPTY;
    }
    else {
      container = VirtualFilePointerManagerEx.getInstance().createContainer(myRootsDisposable);
      ((VirtualFilePointerContainerImpl)container).addAll(urls);
    }
    return container;
  }

  private VirtualFilePointerContainer getOrComputeContainer(@NonNull OrderRootType rootType,
                                                            int flags,
                                                            @NonNull Supplier<? extends Collection<String>> rootUrlsComputer) {
    ConcurrentMap<CacheKey, VirtualFilePointerContainer> map = myRoots.get();
    CacheKey key = new CacheKey(rootType, flags);
    VirtualFilePointerContainer cached = map == null ? null : map.get(key);
    if (cached == null) {
      map = ConcurrencyUtil.cacheOrGet(myRoots, new ConcurrentHashMap<>());
      cached = map.computeIfAbsent(key, __ -> createContainer(rootUrlsComputer.get()));
    }
    return cached == EMPTY ? null : cached;
  }

  public VirtualFile[] getOrComputeRoots(@NonNull OrderRootType rootType, int flags, @NonNull Supplier<? extends Collection<String>> computer) {
    VirtualFilePointerContainer container = getOrComputeContainer(rootType, flags, computer);
    return container == null ? VirtualFile.EMPTY_ARRAY : container.getFiles();
  }

  public String [] getOrComputeUrls(@NonNull OrderRootType rootType, int flags, @NonNull Supplier<? extends Collection<String>> computer) {
    VirtualFilePointerContainer container = getOrComputeContainer(rootType, flags, computer);
    return container == null ? ArrayUtilRt.EMPTY_STRING_ARRAY : container.getUrls();
  }

  public void clearCache() {
//    ApplicationManager.getApplication().assertWriteIntentLockAcquired();
    disposePointers();
    myRoots.set(null);
  }

  protected static final class CacheKey {
    private final OrderRootType myRootType;
    private final int myFlags;

    public CacheKey(@NonNull OrderRootType rootType, int flags) {
      myRootType = rootType;
      myFlags = flags;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

      CacheKey cacheKey = (CacheKey)o;
      return myFlags == cacheKey.myFlags && myRootType.equals(cacheKey.myRootType);

    }

    @Override
    public int hashCode() {
      return 31 * myRootType.hashCode() + myFlags;
    }
  }
}