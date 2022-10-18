package com.tyron.editor.event;

import org.jetbrains.annotations.NotNull;

import java.util.Comparator;

@FunctionalInterface
public interface PrioritizedContentListener extends ContentListener {
  /**
   * Comparator that sorts {@link ContentListener} objects by their {@link PrioritizedContentListener#getPriority() priorities} (if any).
   * <p/>
   * The rules are:
   * <pre>
   * <ul>
   *   <li>{@link PrioritizedContentListener} has more priority than {@link ContentListener};</li>
   *   <li>{@link PrioritizedContentListener} with lower value returned from {@link #getPriority()} has more priority than another;</li>
   * </ul>
   * </pre>
   */
  Comparator<? super ContentListener> COMPARATOR = new Comparator<Object>() {
    @Override
    public int compare(Object o1, Object o2) {
      return Integer.compare(getPriority(o1), getPriority(o2));
    }

    private int getPriority(@NotNull Object o) {
      if (o instanceof PrioritizedContentListener) {
        return ((PrioritizedContentListener)o).getPriority();
      }
      return Integer.MAX_VALUE;
    }
  };

  int getPriority();
}