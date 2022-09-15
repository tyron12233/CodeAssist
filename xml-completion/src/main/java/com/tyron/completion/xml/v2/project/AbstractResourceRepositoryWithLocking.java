package com.tyron.completion.xml.v2.project;

import androidx.annotation.GuardedBy;

import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.ide.common.resources.AbstractResourceRepository;
import com.android.ide.common.resources.ResourceItem;
import com.android.ide.common.resources.ResourceTable;
import com.android.resources.ResourceType;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Wrapper around a {@link ResourceTable} that:
 *
 * <ul>
 *   <li>May compute cells in the table on-demand.
 *   <li>May change in the background, if underlying files or other sources of data have changed.
 *       Because of that access should be synchronized on the {@code ITEM_MAP_LOCK} object.
 * </ul>
 */
public abstract class AbstractResourceRepositoryWithLocking extends AbstractResourceRepository {
  /**
   * The lock used to protect map access.
   *
   * <p>In the IDE, this needs to be obtained <b>AFTER</b> the IDE read/write lock, to avoid
   * deadlocks (most readers of the repository system execute in a read action, so obtaining the
   * locks in opposite order results in deadlocks).
   */
  public static final Object ITEM_MAP_LOCK = new Object();

  @SuppressWarnings("InstanceGuardedByStatic")
  @GuardedBy("ITEM_MAP_LOCK")
  @Nullable
  protected abstract ListMultimap<String, ResourceItem> getMap(
      @NotNull ResourceNamespace namespace, @NotNull ResourceType resourceType);

  @SuppressWarnings("InstanceGuardedByStatic")
  @GuardedBy("ITEM_MAP_LOCK")
  @Override
  @NotNull
  protected ListMultimap<String, ResourceItem> getResourcesInternal(
      @NotNull ResourceNamespace namespace, @NotNull ResourceType resourceType) {
    ListMultimap<String, ResourceItem> map = getMap(namespace, resourceType);
    return map == null ? ImmutableListMultimap.of() : map;
  }

  @Override
  @NotNull
  public List<ResourceItem> getResources(@NotNull ResourceNamespace namespace,
                                         @NotNull ResourceType resourceType,
                                         @NotNull String resourceName) {
    synchronized (ITEM_MAP_LOCK) {
      return super.getResources(namespace, resourceType, resourceName);
    }
  }

  @Override
  @NotNull
  public List<ResourceItem> getResources(@NotNull ResourceNamespace namespace,
                                         @NotNull ResourceType resourceType,
                                         @NotNull Predicate<ResourceItem> filter) {
    synchronized (ITEM_MAP_LOCK) {
      return super.getResources(namespace, resourceType, filter);
    }
  }

  @Override
  @NotNull
  public ListMultimap<String, ResourceItem> getResources(@NotNull ResourceNamespace namespace, @NotNull ResourceType resourceType) {
    synchronized (ITEM_MAP_LOCK) {
      return super.getResources(namespace, resourceType);
    }
  }

  @Override
  @NotNull
  public Set<String> getResourceNames(@NotNull ResourceNamespace namespace, @NotNull ResourceType resourceType) {
    synchronized (ITEM_MAP_LOCK) {
      ListMultimap<String, ResourceItem> map = getMap(namespace, resourceType);
      return map == null ? ImmutableSet.of() : ImmutableSet.copyOf(map.keySet());
    }
  }

  @Override
  public boolean hasResources(@NotNull ResourceNamespace namespace, @NotNull ResourceType resourceType, @NotNull String resourceName) {
    synchronized (ITEM_MAP_LOCK) {
      return super.hasResources(namespace, resourceType, resourceName);
    }
  }

  @Override
  public boolean hasResources(@NotNull ResourceNamespace namespace, @NotNull ResourceType resourceType) {
    synchronized (ITEM_MAP_LOCK) {
      return super.hasResources(namespace, resourceType);
    }
  }

  @Override
  @NotNull
  public Set<ResourceType> getResourceTypes(@NotNull ResourceNamespace namespace) {
    synchronized (ITEM_MAP_LOCK) {
      return super.getResourceTypes(namespace);
    }
  }
}