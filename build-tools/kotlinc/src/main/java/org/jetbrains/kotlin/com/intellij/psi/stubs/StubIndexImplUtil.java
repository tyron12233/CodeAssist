package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexEx;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntIterator;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.IntPredicate;

final class StubIndexImplUtil {
  @NonNull
  static Iterator<VirtualFile> mapIdIterator(@NonNull IntIterator idIterator, @NonNull IntPredicate filter) {
    FileBasedIndexEx fileBasedIndex = (FileBasedIndexEx) FileBasedIndex.getInstance();
    return new Iterator<VirtualFile>() {
      VirtualFile next;
      boolean hasNext;
      {
        findNext();
      }
      @Override
      public boolean hasNext() {
        return hasNext;
      }

      private void findNext() {
        hasNext = false;
        while (idIterator.hasNext()) {
          int id = idIterator.nextInt();
          if (!filter.test(id)) {
            continue;
          }
          VirtualFile t = fileBasedIndex.findFileById(id);
          if (t != null) {
            next = t;
            hasNext = true;
            break;
          }
        }
      }

      @Override
      public VirtualFile next() {
        if (hasNext) {
          VirtualFile result = next;
          findNext();
          return result;
        }
        else {
          throw new NoSuchElementException();
        }
      }

      @Override
      public void remove() {
        idIterator.remove();
      }
    };
  }
}