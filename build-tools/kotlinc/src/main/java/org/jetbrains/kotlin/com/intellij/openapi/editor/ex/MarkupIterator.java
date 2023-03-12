package org.jetbrains.kotlin.com.intellij.openapi.editor.ex;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.util.containers.PeekableIterator;

import java.util.Comparator;
import java.util.NoSuchElementException;

/**
 * An iterator you must {@link #dispose()} after use
 */
public interface MarkupIterator<T> extends PeekableIterator<T> {
  void dispose();

  @SuppressWarnings("rawtypes")
  MarkupIterator EMPTY = new MarkupIterator() {
    @Override
    public void dispose() {
    }

    @Override
    public Object peek() {
      throw new NoSuchElementException();
    }

    @Override
    public boolean hasNext() {
      return false;
    }

    @Override
    public Object next() {
      throw new NoSuchElementException();
    }

    @Override
    public void remove() {
      throw new NoSuchElementException();
    }
  };

  @NotNull
  static <T> MarkupIterator<T> mergeIterators(@NotNull final MarkupIterator<T> iterator1,
                                              @NotNull final MarkupIterator<T> iterator2,
                                              @NotNull final Comparator<? super T> comparator) {
    return new MarkupIterator<T>() {
      @Override
      public void dispose() {
        iterator1.dispose();
        iterator2.dispose();
      }

      @Override
      public boolean hasNext() {
        return iterator1.hasNext() || iterator2.hasNext();
      }

      @Override
      public T next() {
        return choose().next();
      }

      @NotNull
      private MarkupIterator<T> choose() {
        T t1 = iterator1.hasNext() ? iterator1.peek() : null;
        T t2 = iterator2.hasNext() ? iterator2.peek() : null;
        if (t1 == null) {
          return iterator2;
        }
        if (t2 == null) {
          return iterator1;
        }
        int compare = comparator.compare(t1, t2);
        return compare < 0 ? iterator1 : iterator2;
      }

      @Override
      public void remove() {
        throw new NoSuchElementException();
      }

      @Override
      public T peek() {
        return choose().peek();
      }
    };
  }
}