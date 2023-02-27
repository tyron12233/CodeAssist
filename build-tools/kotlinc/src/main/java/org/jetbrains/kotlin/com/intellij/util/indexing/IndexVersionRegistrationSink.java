package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class IndexVersionRegistrationSink {
  private final Map<ID<?, ?>, IndexVersion.IndexVersionDiff> indexVersionDiffs = new ConcurrentHashMap<>();

  public boolean hasChangedIndexes() {
    return ContainerUtil.find(indexVersionDiffs.values(),
            IndexVersionRegistrationSink::isRebuildRequired) != null;
  }

  public @NotNull String changedIndices() {
    return buildString(IndexVersionRegistrationSink::isRebuildRequired);
  }

  public void logChangedAndFullyBuiltIndices(@NotNull Logger log,
                                             @NotNull String changedIndicesLogMessage,
                                             @NotNull String fullyBuiltIndicesLogMessage) {
    String changedIndices = changedIndices();
    if (!changedIndices.isEmpty()) {
      log.info(changedIndicesLogMessage + changedIndices);
    }
    String fullyBuiltIndices = initiallyBuiltIndices();
    if (!fullyBuiltIndices.isEmpty()) {
      log.info(fullyBuiltIndicesLogMessage + fullyBuiltIndices);
    }
  }

  private @NotNull String buildString(@NotNull Predicate<? super IndexVersion.IndexVersionDiff> condition) {
    return indexVersionDiffs
      .entrySet()
      .stream()
      .filter(e -> condition.test(e.getValue()))
      .map(e -> e.getKey().getName() + e.getValue().getLogText())
      .collect(Collectors.joining(","));
  }

  private String initiallyBuiltIndices() {
    return buildString(diff -> diff instanceof IndexVersion.IndexVersionDiff.InitialBuild);
  }

  public <K, V> void setIndexVersionDiff(@NotNull ID<K, V> name, @NotNull IndexVersion.IndexVersionDiff diff) {
    indexVersionDiffs.put(name, diff);
  }

  private static boolean isRebuildRequired(@NotNull IndexVersion.IndexVersionDiff diff) {
    return diff instanceof IndexVersion.IndexVersionDiff.CorruptedRebuild ||
           diff instanceof IndexVersion.IndexVersionDiff.VersionChanged;
  }
}