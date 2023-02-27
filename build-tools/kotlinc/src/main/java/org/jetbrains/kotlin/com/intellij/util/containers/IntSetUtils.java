package org.jetbrains.kotlin.com.intellij.util.containers;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntIterator;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntSet;

import java.util.Collection;

public class IntSetUtils {

    public static IntSet synchronize(IntOpenHashSet integers) {
        return new SynchronizedIntSet(integers, new Object());
    }

    private static class SynchronizedIntSet implements IntSet {

        private final IntSet set;
        private final Object lock;

        public SynchronizedIntSet(IntSet set, Object lock) {
            this.set = set;
            this.lock = lock;
        }

        @Override
        public int size() {
            synchronized (lock) {
                return set.size();
            }
        }

        @Override
        public boolean isEmpty() {
            synchronized (lock) {
                return set.isEmpty();
            }
        }

        @NonNull
        @Override
        public IntIterator iterator() {
            synchronized (lock) {
                return set.iterator();
            }
        }

        @NonNull
        @Override
        public Object[] toArray() {
            synchronized (lock) {
                return set.toArray();
            }
        }

        @NonNull
        @Override
        public <T> T[] toArray(@NonNull T[] ts) {
            synchronized (lock) {
                return set.toArray(ts);
            }
        }

        @Override
        public boolean containsAll(@NonNull Collection<?> collection) {
            synchronized (lock) {
                return set.containsAll(collection);
            }
        }

        @Override
        public boolean addAll(@NonNull Collection<? extends Integer> collection) {
            synchronized (lock) {
                return set.addAll(collection);
            }
        }

        @Override
        public boolean removeAll(@NonNull Collection<?> collection) {
            synchronized (lock) {
                return set.removeAll(collection);
            }
        }

        @Override
        public boolean retainAll(@NonNull Collection<?> collection) {
            synchronized (lock) {
                return set.retainAll(collection);
            }
        }

        @Override
        public void clear() {
            synchronized (lock) {
                set.clear();
            }
        }

        @Override
        public boolean add(int i) {
            synchronized (lock) {
                return set.add(i);
            }
        }

        @Override
        public boolean contains(int i) {
            synchronized (lock) {
                return set.contains(i);
            }
        }

        @Override
        public int[] toIntArray() {
            synchronized (lock) {
                return set.toIntArray();
            }
        }

        @Override
        public boolean remove(int i) {
            synchronized (lock) {
                return set.remove(i);
            }
        }
    }
}
